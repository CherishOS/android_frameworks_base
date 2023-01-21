/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.user.utils

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class MultiUserActionsEvent(val value: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Add User tap from User Switcher.") CREATE_USER_FROM_USER_SWITCHER(1257),
    @UiEvent(doc = "Add Guest tap from User Switcher.") CREATE_GUEST_FROM_USER_SWITCHER(1258),
    @UiEvent(doc = "Add Restricted User tap from User Switcher.")
    CREATE_RESTRICTED_USER_FROM_USER_SWITCHER(1259),
    @UiEvent(doc = "Switch to   User tap from User Switcher.")
    SWITCH_TO_USER_FROM_USER_SWITCHER(1266),
    @UiEvent(doc = "Switch to  Guest User tap from User Switcher.")
    SWITCH_TO_GUEST_FROM_USER_SWITCHER(1267),
    @UiEvent(doc = "Switch to Restricted User tap from User Switcher.")
    SWITCH_TO_RESTRICTED_USER_FROM_USER_SWITCHER(1268),
    @UiEvent(doc = "Grant admin privileges to user on creation from User Switcher.")
    GRANT_ADMIN_FROM_USER_SWITCHER_CREATION_DIALOG(1278),
    @UiEvent(doc = "Not grant admin privileges to user on creation from User Switcher")
    NOT_GRANT_ADMIN_FROM_USER_SWITCHER_CREATION_DIALOG(1279);

    override fun getId(): Int {
        return value
    }
}
