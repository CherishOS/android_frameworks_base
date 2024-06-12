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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.content.packageManager
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.util.time.fakeSystemClock

val Kosmos.screenRecordChipInteractor: ScreenRecordChipInteractor by
    Kosmos.Fixture {
        ScreenRecordChipInteractor(
            scope = applicationCoroutineScope,
            screenRecordRepository = screenRecordRepository,
            dialogFactory = mockSystemUIDialogFactory,
            dialogTransitionAnimator = mockDialogTransitionAnimator,
            systemClock = fakeSystemClock,
        )
    }

val Kosmos.mediaProjectionChipInteractor: MediaProjectionChipInteractor by
    Kosmos.Fixture {
        MediaProjectionChipInteractor(
            scope = applicationCoroutineScope,
            mediaProjectionRepository = fakeMediaProjectionRepository,
            packageManager = packageManager,
            systemClock = fakeSystemClock,
        )
    }

val Kosmos.callChipInteractor: FakeCallChipInteractor by Kosmos.Fixture { FakeCallChipInteractor() }

val Kosmos.ongoingActivityChipsViewModel: OngoingActivityChipsViewModel by
    Kosmos.Fixture {
        OngoingActivityChipsViewModel(
            testScope.backgroundScope,
            screenRecordChipInteractor = screenRecordChipInteractor,
            mediaProjectionChipInteractor = mediaProjectionChipInteractor,
            callChipInteractor = callChipInteractor,
        )
    }
