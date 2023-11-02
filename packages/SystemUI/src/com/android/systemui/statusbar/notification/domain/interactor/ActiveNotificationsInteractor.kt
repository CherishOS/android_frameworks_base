/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ActiveNotificationsInteractor
@Inject
constructor(
    repository: ActiveNotificationListRepository,
) {
    /** Notifications actively presented to the user in the notification stack, in order. */
    val topLevelRepresentativeNotifications: Flow<List<ActiveNotificationModel>> =
        repository.activeNotifications.map { store ->
            store.renderList.map { key ->
                val entry =
                    store[key]
                        ?: error("Could not find notification with key $key in active notif store.")
                when (entry) {
                    is ActiveNotificationGroupModel -> entry.summary
                    is ActiveNotificationModel -> entry
                }
            }
        }
}
