/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.session.MediaSession

/** State of a media view. */
data class MediaData(
    val initialized: Boolean = false,
    val backgroundColor: Int,
    val app: String?,
    val appIcon: Drawable?,
    val artist: CharSequence?,
    val song: CharSequence?,
    val artwork: Icon?,
    val actions: List<MediaAction>,
    val actionsToShowInCompact: List<Int>,
    val packageName: String,
    val token: MediaSession.Token?,
    val clickIntent: PendingIntent?,
    val device: MediaDeviceData?,
    var resumeAction: Runnable?,
    val notificationKey: String = "INVALID",
    var hasCheckedForResume: Boolean = false
)

/** State of a media action. */
data class MediaAction(
    val drawable: Drawable?,
    val action: Runnable?,
    val contentDescription: CharSequence?
)

/** State of the media device. */
data class MediaDeviceData(
    val enabled: Boolean,
    val icon: Drawable?,
    val name: String?
)
