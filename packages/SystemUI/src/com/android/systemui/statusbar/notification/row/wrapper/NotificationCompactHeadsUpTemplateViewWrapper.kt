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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper

import android.content.Context
import android.view.View
import com.android.systemui.statusbar.notification.FeedbackIcon
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/**
 * Compact Heads up Notifications template that doesn't set feedback icon and audibly alert icons
 */
open class NotificationCompactHeadsUpTemplateViewWrapper(
    ctx: Context,
    view: View,
    row: ExpandableNotificationRow
) : NotificationTemplateViewWrapper(ctx, view, row) {
    override fun setFeedbackIcon(icon: FeedbackIcon?) = Unit
    override fun setRecentlyAudiblyAlerted(audiblyAlerted: Boolean) = Unit
}
