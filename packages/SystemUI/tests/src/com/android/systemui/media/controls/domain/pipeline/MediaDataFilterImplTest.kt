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

package com.android.systemui.media.controls.domain.pipeline

import android.app.smartspace.SmartspaceAction
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.shared.model.EXTRA_KEY_TRIGGER_RESUME
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.MediaDataLoadingModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaLoadingModel
import com.android.systemui.media.controls.ui.controller.MediaPlayerData
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val KEY = "TEST_KEY"
private const val KEY_ALT = "TEST_KEY_2"
private const val USER_MAIN = 0
private const val USER_GUEST = 10
private const val PRIVATE_PROFILE = 12
private const val PACKAGE = "PKG"
private val INSTANCE_ID = InstanceId.fakeInstanceId(123)!!
private val INSTANCE_ID_GUEST = InstanceId.fakeInstanceId(321)!!
private const val APP_UID = 99
private const val SMARTSPACE_KEY = "SMARTSPACE_KEY"
private const val SMARTSPACE_PACKAGE = "SMARTSPACE_PKG"
private val SMARTSPACE_INSTANCE_ID = InstanceId.fakeInstanceId(456)!!

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaDataFilterImplTest : SysuiTestCase() {

    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var broadcastSender: BroadcastSender
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var lockscreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var executor: Executor
    @Mock private lateinit var smartspaceData: SmartspaceMediaData
    @Mock private lateinit var smartspaceMediaRecommendationItem: SmartspaceAction
    @Mock private lateinit var logger: MediaUiEventLogger
    @Mock private lateinit var mediaFlags: MediaFlags
    @Mock private lateinit var cardAction: SmartspaceAction

    private lateinit var mediaDataFilter: MediaDataFilterImpl
    private lateinit var repository: MediaFilterRepository
    private lateinit var testScope: TestScope
    private lateinit var dataMain: MediaData
    private lateinit var dataGuest: MediaData
    private lateinit var dataPrivateProfile: MediaData
    private val clock = FakeSystemClock()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        MediaPlayerData.clear()
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(false)
        testScope = TestScope()
        repository = MediaFilterRepository()
        mediaDataFilter =
            MediaDataFilterImpl(
                context,
                userTracker,
                broadcastSender,
                lockscreenUserManager,
                executor,
                clock,
                logger,
                mediaFlags,
                repository,
            )
        mediaDataFilter.mediaDataManager = mediaDataManager

        // Start all tests as main user
        setUser(USER_MAIN)

        // Set up test media data
        dataMain =
            MediaTestUtils.emptyMediaData.copy(
                userId = USER_MAIN,
                packageName = PACKAGE,
                instanceId = INSTANCE_ID,
                appUid = APP_UID
            )
        dataGuest = dataMain.copy(userId = USER_GUEST, instanceId = INSTANCE_ID_GUEST)
        dataPrivateProfile = dataMain.copy(userId = PRIVATE_PROFILE, instanceId = INSTANCE_ID_GUEST)

        whenever(smartspaceData.targetId).thenReturn(SMARTSPACE_KEY)
        whenever(smartspaceData.isActive).thenReturn(true)
        whenever(smartspaceData.isValid()).thenReturn(true)
        whenever(smartspaceData.packageName).thenReturn(SMARTSPACE_PACKAGE)
        whenever(smartspaceData.recommendations)
            .thenReturn(listOf(smartspaceMediaRecommendationItem))
        whenever(smartspaceData.headphoneConnectionTimeMillis)
            .thenReturn(clock.currentTimeMillis() - 100)
        whenever(smartspaceData.instanceId).thenReturn(SMARTSPACE_INSTANCE_ID)
        whenever(smartspaceData.cardAction).thenReturn(cardAction)
    }

    private fun setUser(id: Int) {
        whenever(lockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(false)
        whenever(lockscreenUserManager.isProfileAvailable(anyInt())).thenReturn(false)
        whenever(lockscreenUserManager.isCurrentProfile(eq(id))).thenReturn(true)
        whenever(lockscreenUserManager.isProfileAvailable(eq(id))).thenReturn(true)
        whenever(lockscreenUserManager.isProfileAvailable(eq(PRIVATE_PROFILE))).thenReturn(true)
        mediaDataFilter.handleUserSwitched()
    }

    private fun setPrivateProfileUnavailable() {
        whenever(lockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(false)
        whenever(lockscreenUserManager.isCurrentProfile(eq(USER_MAIN))).thenReturn(true)
        whenever(lockscreenUserManager.isCurrentProfile(eq(PRIVATE_PROFILE))).thenReturn(true)
        whenever(lockscreenUserManager.isProfileAvailable(eq(PRIVATE_PROFILE))).thenReturn(false)
        mediaDataFilter.handleProfileChanged()
    }

    @Test
    fun onDataLoadedForCurrentUser_updatesLoadedStates() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val mediaDataLoadingModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaDataLoadingModel)
        }

    @Test
    fun onDataLoadedForGuest_doesNotUpdateLoadedStates() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            mediaDataFilter.onMediaDataLoaded(KEY, null, dataGuest)

            assertThat(mediaDataLoadedStates).isNotEqualTo(mediaLoadedStatesModel)
        }

    @Test
    fun onRemovedForCurrent_updatesLoadedStates() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val mediaLoadedStatesModel =
                mutableListOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            // GIVEN a media was removed for main user
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            mediaLoadedStatesModel.remove(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            mediaDataFilter.onMediaDataRemoved(KEY)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)
        }

    @Test
    fun onRemovedForGuest_doesNotUpdateLoadedStates() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)

            // GIVEN a media was removed for guest user
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataGuest)
            mediaDataFilter.onMediaDataRemoved(KEY)

            assertThat(mediaDataLoadedStates).isEmpty()
        }

    @Test
    fun onUserSwitched_removesOldUserControls() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            // GIVEN that we have a media loaded for main user
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // and we switch to guest user
            setUser(USER_GUEST)

            // THEN we should remove the main user's media
            assertThat(mediaDataLoadedStates).isEmpty()
        }

    @Test
    fun onUserSwitched_addsNewUserControls() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val guestLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataGuest.instanceId))
            val mainLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            // GIVEN that we had some media for both users
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
            mediaDataFilter.onMediaDataLoaded(KEY_ALT, null, dataGuest)

            // and we switch to guest user
            setUser(USER_GUEST)

            assertThat(mediaDataLoadedStates).isEqualTo(guestLoadedStatesModel)
            assertThat(mediaDataLoadedStates).isNotEqualTo(mainLoadedStatesModel)
        }

    @Test
    fun onProfileChanged_profileUnavailable_updateStates() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)

            // GIVEN that we had some media for both profiles
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
            mediaDataFilter.onMediaDataLoaded(KEY_ALT, null, dataPrivateProfile)

            // and we change profile status
            setPrivateProfileUnavailable()

            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            // THEN we should remove the private profile media
            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)
        }

    @Test
    fun hasAnyMedia_mediaSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)

            assertThat(hasAnyMedia(selectedUserEntries)).isTrue()
        }

    @Test
    fun hasAnyMedia_recommendationSet_returnsFalse() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(hasAnyMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun hasAnyMediaOrRecommendation_mediaSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)

            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isTrue()
        }

    @Test
    fun hasAnyMediaOrRecommendation_recommendationSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isTrue()
        }

    @Test
    fun hasActiveMedia_inactiveMediaSet_returnsFalse() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)

            val data = dataMain.copy(active = false)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun hasActiveMedia_activeMediaSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val data = dataMain.copy(active = true)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

            assertThat(hasActiveMedia(selectedUserEntries)).isTrue()
        }

    @Test
    fun hasActiveMediaOrRecommendation_inactiveMediaSet_returnsFalse() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val data = dataMain.copy(active = false)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
        }

    @Test
    fun hasActiveMediaOrRecommendation_activeMediaSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val data = dataMain.copy(active = true)
            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)

            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
        }

    @Test
    fun hasActiveMediaOrRecommendation_inactiveRecommendationSet_returnsFalse() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            whenever(smartspaceData.isActive).thenReturn(false)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
        }

    @Test
    fun hasActiveMediaOrRecommendation_invalidRecommendationSet_returnsFalse() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            whenever(smartspaceData.isValid()).thenReturn(false)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
        }

    @Test
    fun hasActiveMediaOrRecommendation_activeAndValidRecommendationSet_returnsTrue() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            whenever(smartspaceData.isActive).thenReturn(true)
            whenever(smartspaceData.isValid()).thenReturn(true)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
        }

    @Test
    fun hasAnyMediaOrRecommendation_onlyCurrentUser() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isFalse()

            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataGuest)
            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isFalse()
            assertThat(hasAnyMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun hasActiveMediaOrRecommendation_onlyCurrentUser() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            val data = dataGuest.copy(active = true)

            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = data)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasAnyMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun onNotificationRemoved_doesNotHaveMedia() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)

            mediaDataFilter.onMediaDataLoaded(KEY, oldKey = null, data = dataMain)
            mediaDataFilter.onMediaDataRemoved(KEY)
            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isFalse()
            assertThat(hasAnyMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun onSwipeToDismiss_setsTimedOut() {
        mediaDataFilter.onMediaDataLoaded(KEY, null, dataMain)
        mediaDataFilter.onSwipeToDismiss()

        verify(mediaDataManager).setInactive(eq(KEY), eq(true), eq(true))
    }

    @Test
    fun onSmartspaceMediaDataLoaded_noMedia_activeValidRec_prioritizesSmartspace() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel =
                SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY, isPrioritized = true)

            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
            verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
            verify(logger, never()).logRecommendationActivated(any(), any(), any())
        }

    @Test
    fun onSmartspaceMediaDataLoaded_noMedia_inactiveRec_showsNothing() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)

            whenever(smartspaceData.isActive).thenReturn(false)

            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(recommendationsLoadingState).isEqualTo(SmartspaceMediaLoadingModel.Unknown)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
            verify(logger, never()).logRecommendationAdded(any(), any())
            verify(logger, never()).logRecommendationActivated(any(), any(), any())
        }

    @Test
    fun onSmartspaceMediaDataLoaded_noRecentMedia_activeValidRec_prioritizesSmartspace() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel =
                SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY, isPrioritized = true)
            val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
            clock.advanceTime(MediaDataFilterImpl.SMARTSPACE_MAX_AGE + 100)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
            verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
            verify(logger, never()).logRecommendationActivated(any(), any(), any())
        }

    @Test
    fun onSmartspaceMediaDataLoaded_noRecentMedia_inactiveRec_showsNothing() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            whenever(smartspaceData.isActive).thenReturn(false)

            val dataOld = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataOld)
            clock.advanceTime(MediaDataFilterImpl.SMARTSPACE_MAX_AGE + 100)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(recommendationsLoadingState).isEqualTo(SmartspaceMediaLoadingModel.Unknown)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
            verify(logger, never()).logRecommendationAdded(any(), any())
            verify(logger, never()).logRecommendationActivated(any(), any(), any())
        }

    @Test
    fun onSmartspaceMediaDataLoaded_hasRecentMedia_inactiveRec_showsNothing() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)

            whenever(smartspaceData.isActive).thenReturn(false)

            // WHEN we have media that was recently played, but not currently active
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // AND we get a smartspace signal
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // THEN we should treat the media as not active instead
            assertThat(recommendationsLoadingState).isEqualTo(SmartspaceMediaLoadingModel.Unknown)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
            verify(logger, never()).logRecommendationAdded(any(), any())
            verify(logger, never()).logRecommendationActivated(any(), any(), any())
        }

    @Test
    fun onSmartspaceMediaDataLoaded_hasRecentMedia_activeInvalidRec_usesMedia() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            whenever(smartspaceData.isValid()).thenReturn(false)

            // WHEN we have media that was recently played, but not currently active
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // AND we get a smartspace signal
            runCurrent()
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // THEN we should treat the media as active instead
            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
            // Smartspace update shouldn't be propagated for the empty rec list.
            assertThat(recommendationsLoadingState).isEqualTo(SmartspaceMediaLoadingModel.Unknown)
            verify(logger, never()).logRecommendationAdded(any(), any())
            verify(logger).logRecommendationActivated(eq(APP_UID), eq(PACKAGE), eq(INSTANCE_ID))
        }

    @Test
    fun onSmartspaceMediaDataLoaded_hasRecentMedia_activeValidRec_usesBoth() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            // WHEN we have media that was recently played, but not currently active
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            val mediaDataLoadingModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY)

            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaDataLoadingModel)

            // AND we get a smartspace signal
            runCurrent()
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // THEN we should treat the media as active instead
            assertThat(mediaDataLoadedStates).isEqualTo(mediaDataLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
            // Smartspace update should also be propagated but not prioritized.
            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            verify(logger).logRecommendationAdded(SMARTSPACE_PACKAGE, SMARTSPACE_INSTANCE_ID)
            verify(logger).logRecommendationActivated(eq(APP_UID), eq(PACKAGE), eq(INSTANCE_ID))
        }

    @Test
    fun onSmartspaceMediaDataRemoved_usedSmartspace_clearsSmartspace() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Removed(SMARTSPACE_KEY)

            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)
            mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun onSmartspaceMediaDataRemoved_usedMediaAndSmartspace_clearsBoth() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Removed(SMARTSPACE_KEY)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)
            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            runCurrent()
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            mediaDataFilter.onSmartspaceMediaDataRemoved(SMARTSPACE_KEY)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasActiveMedia(selectedUserEntries)).isFalse()
        }

    @Test
    fun onSmartspaceLoaded_persistentEnabled_isInactive() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY)
            whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
            whenever(smartspaceData.isActive).thenReturn(false)

            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isTrue()
        }

    @Test
    fun onSmartspaceLoaded_persistentEnabled_inactive_hasRecentMedia_staysInactive() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)
            whenever(smartspaceData.isActive).thenReturn(false)

            // If there is media that was recently played but inactive
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // And an inactive recommendation is loaded
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // Smartspace is loaded but the media stays inactive
            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isFalse()
            assertThat(hasAnyMediaOrRecommendation(selectedUserEntries, smartspaceMediaData))
                .isTrue()
        }

    @Test
    fun onSwipeToDismiss_persistentEnabled_recommendationSetInactive() {
        whenever(mediaFlags.isPersistentSsCardEnabled()).thenReturn(true)

        val data =
            EMPTY_SMARTSPACE_MEDIA_DATA.copy(
                targetId = SMARTSPACE_KEY,
                isActive = true,
                packageName = SMARTSPACE_PACKAGE,
                recommendations = listOf(smartspaceMediaRecommendationItem),
            )
        mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, data)
        mediaDataFilter.onSwipeToDismiss()

        verify(mediaDataManager).setRecommendationInactive(eq(SMARTSPACE_KEY))
        verify(mediaDataManager, never())
            .dismissSmartspaceRecommendation(eq(SMARTSPACE_KEY), anyLong())
    }

    @Test
    fun smartspaceLoaded_shouldTriggerResume_doesTrigger() =
        testScope.runTest {
            val selectedUserEntries by collectLastValue(repository.selectedUserEntries)
            val smartspaceMediaData by collectLastValue(repository.smartspaceMediaData)
            val reactivatedKey by collectLastValue(repository.reactivatedId)
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))
            // WHEN we have media that was recently played, but not currently active
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // AND we get a smartspace signal with extra to trigger resume
            runCurrent()
            val extras = Bundle().apply { putBoolean(EXTRA_KEY_TRIGGER_RESUME, true) }
            whenever(cardAction.extras).thenReturn(extras)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // THEN we should treat the media as active instead
            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)
            assertThat(
                    hasActiveMediaOrRecommendation(
                        selectedUserEntries,
                        smartspaceMediaData,
                        reactivatedKey
                    )
                )
                .isTrue()
            // And update the smartspace data state, but not prioritized
            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
        }

    @Test
    fun smartspaceLoaded_notShouldTriggerResume_doesNotTrigger() =
        testScope.runTest {
            val mediaDataLoadedStates by collectLastValue(repository.mediaDataLoadedStates)
            val recommendationsLoadingState by
                collectLastValue(repository.recommendationsLoadingState)
            val recommendationsLoadingModel = SmartspaceMediaLoadingModel.Loaded(SMARTSPACE_KEY)
            val mediaLoadedStatesModel = listOf(MediaDataLoadingModel.Loaded(dataMain.instanceId))

            // WHEN we have media that was recently played, but not currently active
            val dataCurrent = dataMain.copy(active = false, lastActive = clock.elapsedRealtime())
            mediaDataFilter.onMediaDataLoaded(KEY, null, dataCurrent)

            assertThat(mediaDataLoadedStates).isEqualTo(mediaLoadedStatesModel)

            // AND we get a smartspace signal with extra to not trigger resume
            val extras = Bundle().apply { putBoolean(EXTRA_KEY_TRIGGER_RESUME, false) }
            whenever(cardAction.extras).thenReturn(extras)
            mediaDataFilter.onSmartspaceMediaDataLoaded(SMARTSPACE_KEY, smartspaceData)

            // But the smartspace update is still propagated
            assertThat(recommendationsLoadingState).isEqualTo(recommendationsLoadingModel)
        }

    private fun hasActiveMediaOrRecommendation(
        entries: Map<InstanceId, MediaData>?,
        smartspaceMediaData: SmartspaceMediaData?,
        reactivatedId: InstanceId?
    ): Boolean {
        if (entries == null || smartspaceMediaData == null) {
            return false
        }
        return entries.any { it.value.active } ||
            (smartspaceMediaData.isActive &&
                (smartspaceMediaData.isValid() || reactivatedId != null))
    }

    private fun hasActiveMedia(entries: Map<InstanceId, MediaData>?): Boolean {
        return entries?.any { it.value.active } ?: false
    }

    private fun hasAnyMediaOrRecommendation(
        entries: Map<InstanceId, MediaData>?,
        smartspaceMediaData: SmartspaceMediaData?
    ): Boolean {
        if (entries == null || smartspaceMediaData == null) {
            return false
        }
        return entries.isNotEmpty() ||
            (if (mediaFlags.isPersistentSsCardEnabled()) {
                smartspaceMediaData.isValid()
            } else {
                smartspaceMediaData.isActive && smartspaceMediaData.isValid()
            })
    }

    private fun hasAnyMedia(entries: Map<InstanceId, MediaData>?): Boolean {
        return entries?.isNotEmpty() ?: false
    }
}
