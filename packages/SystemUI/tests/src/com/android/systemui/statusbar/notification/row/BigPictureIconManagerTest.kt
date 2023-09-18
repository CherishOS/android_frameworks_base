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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification.row

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.widget.NotificationDrawableConsumer
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

private const val FREE_IMAGE_DELAY_MS = 4000L

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class BigPictureIconManagerTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testableResources = context.orCreateTestableResources
    private val imageLoader: ImageLoader = ImageLoader(context, testDispatcher)
    private var mockConsumer: NotificationDrawableConsumer = mock()
    private val drawableCaptor = argumentCaptor<Drawable>()

    private lateinit var iconManager: BigPictureIconManager

    private val expectedDrawable by lazy {
        context.resources.getDrawable(R.drawable.dessert_zombiegingerbread, context.theme)
    }
    private val supportedIcon by lazy {
        Icon.createWithContentUri(
            Uri.parse(
                "android.resource://${context.packageName}/${R.drawable.dessert_zombiegingerbread}"
            )
        )
    }
    private val unsupportedIcon by lazy {
        Icon.createWithBitmap(
            BitmapFactory.decodeResource(context.resources, R.drawable.dessert_zombiegingerbread)
        )
    }
    private val invalidIcon by lazy { Icon.createWithContentUri(Uri.parse("this.is/broken")) }

    @Before
    fun setUp() {
        iconManager =
            BigPictureIconManager(
                context,
                imageLoader,
                scope = testScope,
                mainDispatcher = testDispatcher,
                bgDispatcher = testDispatcher
            )
    }

    @Test
    fun onIconUpdated_supportedType_placeholderLoaded() =
        testScope.runTest {
            // WHEN update with a supported icon
            iconManager.updateIcon(mockConsumer, supportedIcon).run()

            // THEN consumer is updated with a placeholder
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsPlaceHolder(drawableCaptor.value)
            assertSize(drawableCaptor.value)
        }

    @Test
    fun onIconUpdated_notSupportedType_fullImageLoaded() =
        testScope.runTest {
            // WHEN update with an unsupported icon
            iconManager.updateIcon(mockConsumer, unsupportedIcon).run()

            // THEN consumer is updated with the full image
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsFullImage(drawableCaptor.value)
            assertSize(drawableCaptor.value)
        }

    @Test
    fun onIconUpdated_invalidIcon_drawableIsNull() =
        testScope.runTest {
            // WHEN update with an invalid icon
            iconManager.updateIcon(mockConsumer, invalidIcon).run()

            // THEN consumer is updated with null
            verify(mockConsumer).setImageDrawable(null)
        }

    @Test
    fun onIconUpdated_consumerAlreadySet_nothingHappens() =
        testScope.runTest {
            // GIVEN a consumer is set
            val otherConsumer: NotificationDrawableConsumer = mock()
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            reset(mockConsumer)

            // WHEN a new consumer is set
            iconManager.updateIcon(otherConsumer, unsupportedIcon).run()

            // THEN nothing happens
            verifyZeroInteractions(mockConsumer, otherConsumer)
        }

    @Test
    fun onIconUpdated_iconAlreadySet_loadsNewIcon() =
        testScope.runTest {
            // GIVEN an icon is set
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            reset(mockConsumer)

            // WHEN a new icon is set
            iconManager.updateIcon(mockConsumer, unsupportedIcon).run()

            // THEN consumer is updated with the new image
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsFullImage(drawableCaptor.value)
            assertSize(drawableCaptor.value)
        }

    @Test
    fun onIconUpdated_supportedTypeButTooWide_resizedPlaceholderLoaded() =
        testScope.runTest {
            // GIVEN the max width is smaller than our image
            testableResources.addOverride(
                com.android.internal.R.dimen.notification_big_picture_max_width,
                20
            )
            iconManager.updateMaxImageSizes()

            // WHEN update with a supported icon
            iconManager.updateIcon(mockConsumer, supportedIcon).run()

            // THEN consumer is updated with the resized placeholder
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsPlaceHolder(drawableCaptor.value)
            assertSize(drawableCaptor.value, expectedWidth = 20, expectedHeight = 20)
        }

    @Test
    fun onIconUpdated_supportedTypeButTooHigh_resizedPlaceholderLoaded() =
        testScope.runTest {
            // GIVEN the max height is smaller than our image
            testableResources.addOverride(
                com.android.internal.R.dimen.notification_big_picture_max_height,
                20
            )
            iconManager.updateMaxImageSizes()

            // WHEN update with a supported icon
            iconManager.updateIcon(mockConsumer, supportedIcon).run()

            // THEN consumer is updated with the resized placeholder
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsPlaceHolder(drawableCaptor.value)
            assertSize(drawableCaptor.value, expectedWidth = 20, expectedHeight = 20)
        }

    @Test
    fun onViewShown_placeholderShowing_fullImageLoaded() =
        testScope.runTest {
            // GIVEN placeholder is showing
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            reset(mockConsumer)

            // WHEN the view is shown
            iconManager.onViewShown(true)
            runCurrent()

            // THEN full image is set
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsFullImage(drawableCaptor.value)
            assertSize(drawableCaptor.value)
        }

    @Test
    fun onViewHidden_fullImageShowing_placeHolderSet() =
        testScope.runTest {
            // GIVEN full image is showing and the view is shown
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            iconManager.onViewShown(true)
            runCurrent()
            reset(mockConsumer)

            // WHEN the view goes off the screen
            iconManager.onViewShown(false)
            // AND we wait a bit
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()

            // THEN placeholder is set
            verify(mockConsumer).setImageDrawable(drawableCaptor.capture())
            assertIsPlaceHolder(drawableCaptor.value)
            assertSize(drawableCaptor.value)
        }

    @Test
    fun onViewShownToggled_viewShown_nothingHappens() =
        testScope.runTest {
            // GIVEN full image is showing and the view is shown
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            iconManager.onViewShown(true)
            runCurrent()
            reset(mockConsumer)

            // WHEN the onViewShown is toggled
            iconManager.onViewShown(false)
            runCurrent()
            iconManager.onViewShown(true)
            // AND we wait a bit
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()

            // THEN nothing happens
            verifyZeroInteractions(mockConsumer)
        }

    // nice to have tests

    @Test
    fun onViewShown_fullImageLoaded_nothingHappens() =
        testScope.runTest {
            // GIVEN full image is showing
            iconManager.updateIcon(mockConsumer, unsupportedIcon).run()
            reset(mockConsumer)

            // WHEN the view is shown
            iconManager.onViewShown(true)
            runCurrent()

            // THEN nothing happens
            verifyZeroInteractions(mockConsumer)
        }

    @Test
    fun onViewHidden_placeholderShowing_nothingHappens() =
        testScope.runTest {
            // GIVEN placeholder image is showing
            iconManager.updateIcon(mockConsumer, unsupportedIcon).run()
            reset(mockConsumer)

            // WHEN the view is hidden
            iconManager.onViewShown(false)
            // AND we wait a bit
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()

            // THEN nothing happens
            verifyZeroInteractions(mockConsumer)
        }

    @Test
    fun onViewShown_alreadyShowing_nothingHappens() =
        testScope.runTest {
            // GIVEN full image is showing and the view is shown
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            iconManager.onViewShown(true)
            runCurrent()
            reset(mockConsumer)

            // WHEN view shown called again
            iconManager.onViewShown(true)
            runCurrent()

            verifyZeroInteractions(mockConsumer)
        }

    @Test
    fun onViewHidden_alreadyHidden_nothingHappens() =
        testScope.runTest {
            // GIVEN placeholder image is showing and the view is hidden
            iconManager.updateIcon(mockConsumer, unsupportedIcon).run()
            iconManager.onViewShown(false)
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()
            reset(mockConsumer)

            // WHEN the view is hidden again
            iconManager.onViewShown(false)
            // AND we wait a bit
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()

            // THEN nothing happens
            verifyZeroInteractions(mockConsumer)
        }

    @Test
    fun cancelJobs_freeImageJobRunning_jobCancelled() =
        testScope.runTest {
            // GIVEN full image is showing
            iconManager.updateIcon(mockConsumer, supportedIcon).run()
            iconManager.onViewShown(true)
            runCurrent()
            reset(mockConsumer)
            // AND the view has just gone off the screen
            iconManager.onViewShown(false)

            // WHEN cancelJobs is called
            iconManager.cancelJobs()
            // AND we wait a bit
            advanceTimeBy(FREE_IMAGE_DELAY_MS)
            runCurrent()

            // THEN no more updates are happening
            verifyZeroInteractions(mockConsumer)
        }

    private fun assertIsPlaceHolder(drawable: Drawable) {
        assertThat(drawable).isInstanceOf(PlaceHolderDrawable::class.java)
    }

    private fun assertIsFullImage(drawable: Drawable) {
        assertThat(drawable).isInstanceOf(BitmapDrawable::class.java)
    }

    private fun assertSize(
        drawable: Drawable,
        expectedWidth: Int = expectedDrawable.intrinsicWidth,
        expectedHeight: Int = expectedDrawable.intrinsicHeight
    ) {
        assertThat(drawable.intrinsicWidth).isEqualTo(expectedWidth)
        assertThat(drawable.intrinsicHeight).isEqualTo(expectedHeight)
    }
}
