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

package com.android.systemui.statusbar.chips.mediaprojection.domain.interactor

import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor.Companion.createDialogLaunchOnClickListener
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndCastToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndShareToAppDialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.Utils
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Interactor for media-projection-related chips in the status bar.
 *
 * There are two kinds of media projection events that will show chips in the status bar:
 * 1) Share-to-app: Sharing your phone screen content to another app on the same device. (Triggered
 *    from within each individual app.)
 * 2) Cast-to-other-device: Sharing your phone screen content to a different device. (Triggered from
 *    the Quick Settings Cast tile or from the Settings app.) This interactor handles both of those
 *    event types (though maybe not audio-only casting -- see b/342169876).
 */
@SysUISingleton
class MediaProjectionChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val mediaProjectionRepository: MediaProjectionRepository,
    private val packageManager: PackageManager,
    private val systemClock: SystemClock,
    private val dialogFactory: SystemUIDialog.Factory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) : OngoingActivityChipInteractor {
    override val chip: StateFlow<OngoingActivityChipModel> =
        mediaProjectionRepository.mediaProjectionState
            .map { state ->
                when (state) {
                    is MediaProjectionState.NotProjecting -> OngoingActivityChipModel.Hidden
                    is MediaProjectionState.Projecting -> {
                        if (isProjectionToOtherDevice(state.hostPackage)) {
                            createCastToOtherDeviceChip()
                        } else {
                            createShareToAppChip()
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)

    /** Stops the currently active projection. */
    fun stopProjecting() {
        scope.launch { mediaProjectionRepository.stopProjecting() }
    }

    /**
     * Returns true iff projecting to the given [packageName] means that we're projecting to a
     * *different* device (as opposed to projecting to some application on *this* device).
     */
    private fun isProjectionToOtherDevice(packageName: String?): Boolean {
        // The [isHeadlessRemoteDisplayProvider] check approximates whether a projection is to a
        // different device or the same device, because headless remote display packages are the
        // only kinds of packages that do cast-to-other-device. This isn't exactly perfect,
        // because it means that any projection by those headless remote display packages will be
        // marked as going to a different device, even if that isn't always true. See b/321078669.
        return Utils.isHeadlessRemoteDisplayProvider(packageManager, packageName)
    }

    private fun createCastToOtherDeviceChip(): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown(
            icon =
                Icon.Resource(
                    CAST_TO_OTHER_DEVICE_ICON,
                    ContentDescription.Resource(R.string.accessibility_casting)
                ),
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(
                castToOtherDeviceDialogDelegate,
                dialogTransitionAnimator,
            ),
        )
    }

    private val castToOtherDeviceDialogDelegate =
        EndCastToOtherDeviceDialogDelegate(
            dialogFactory,
            this@MediaProjectionChipInteractor,
        )

    private fun createShareToAppChip(): OngoingActivityChipModel.Shown {
        return OngoingActivityChipModel.Shown(
            // TODO(b/332662551): Use the right content description.
            icon = Icon.Resource(SHARE_TO_APP_ICON, contentDescription = null),
            // TODO(b/332662551): Maybe use a MediaProjection API to fetch this time.
            startTimeMs = systemClock.elapsedRealtime(),
            createDialogLaunchOnClickListener(shareToAppDialogDelegate, dialogTransitionAnimator),
        )
    }

    private val shareToAppDialogDelegate =
        EndShareToAppDialogDelegate(
            dialogFactory,
            this@MediaProjectionChipInteractor,
        )

    companion object {
        // TODO(b/332662551): Use the right icon.
        @DrawableRes val SHARE_TO_APP_ICON = R.drawable.ic_screenshot_share
        @DrawableRes val CAST_TO_OTHER_DEVICE_ICON = R.drawable.ic_cast_connected
    }
}
