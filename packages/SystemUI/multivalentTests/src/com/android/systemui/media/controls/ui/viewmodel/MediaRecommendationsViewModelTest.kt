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

package com.android.systemui.media.controls.ui.viewmodel

import android.R
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.MediaTestHelper
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.mediaDataFilter
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaRecommendationsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mediaDataFilter: MediaDataFilterImpl = kosmos.mediaDataFilter
    private val packageManager = kosmos.packageManager
    private val icon: Icon = Icon.createWithResource(context, R.drawable.ic_media_play)
    private val drawable = context.getDrawable(R.drawable.ic_media_play)
    private val smartspaceMediaData: SmartspaceMediaData =
        SmartspaceMediaData(
            targetId = KEY_MEDIA_SMARTSPACE,
            isActive = true,
            packageName = PACKAGE_NAME,
            recommendations = MediaTestHelper.getValidRecommendationList(icon),
        )

    private val underTest: MediaRecommendationsViewModel = kosmos.mediaRecommendationsViewModel

    @Test
    fun loadRecommendations_recsCardViewModelIsLoaded() =
        testScope.runTest {
            whenever(packageManager.getApplicationIcon(Mockito.anyString())).thenReturn(drawable)
            whenever(packageManager.getApplicationIcon(any(ApplicationInfo::class.java)))
                .thenReturn(drawable)
            whenever(packageManager.getApplicationInfo(eq(PACKAGE_NAME), ArgumentMatchers.anyInt()))
                .thenReturn(ApplicationInfo())
            whenever(packageManager.getApplicationLabel(any())).thenReturn(PACKAGE_NAME)
            val recsCardViewModel by collectLastValue(underTest.mediaRecsCard)

            context.setMockPackageManager(packageManager)

            mediaDataFilter.onSmartspaceMediaDataLoaded(KEY_MEDIA_SMARTSPACE, smartspaceMediaData)

            assertThat(recsCardViewModel).isNotNull()
            assertThat(recsCardViewModel?.mediaRecs?.size)
                .isEqualTo(smartspaceMediaData.recommendations.size)
        }

    companion object {
        private const val KEY_MEDIA_SMARTSPACE = "MEDIA_SMARTSPACE_ID"
        private const val PACKAGE_NAME = "com.example.app"
    }
}
