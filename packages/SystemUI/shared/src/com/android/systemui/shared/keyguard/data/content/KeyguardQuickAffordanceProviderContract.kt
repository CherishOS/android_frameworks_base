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
 *
 */

package com.android.systemui.shared.keyguard.data.content

import android.content.ContentResolver
import android.net.Uri

/** Contract definitions for querying content about keyguard quick affordances. */
object KeyguardQuickAffordanceProviderContract {

    const val AUTHORITY = "com.android.systemui.keyguard.quickaffordance"
    const val PERMISSION = "android.permission.ACCESS_KEYGUARD_QUICK_AFFORDANCES"

    private val BASE_URI: Uri =
        Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build()

    /**
     * Table for slots.
     *
     * Slots are positions where affordances can be placed on the lock screen. Affordances that are
     * placed on slots are said to be "selected". The system supports the idea of multiple
     * affordances per slot, though the implementation may limit the number of affordances on each
     * slot.
     *
     * Supported operations:
     * - Query - to know which slots are available, query the [SlotTable.URI] [Uri]. The result set
     * will contain rows with the [SlotTable.Columns] columns.
     */
    object SlotTable {
        const val TABLE_NAME = "slots"
        val URI: Uri = BASE_URI.buildUpon().path(TABLE_NAME).build()

        object Columns {
            /** String. Unique ID for this slot. */
            const val ID = "id"
            /** Integer. The maximum number of affordances that can be placed in the slot. */
            const val CAPACITY = "capacity"
        }
    }

    /**
     * Table for affordances.
     *
     * Affordances are actions/buttons that the user can execute. They are placed on slots on the
     * lock screen.
     *
     * Supported operations:
     * - Query - to know about all the affordances that are available on the device, regardless of
     * which ones are currently selected, query the [AffordanceTable.URI] [Uri]. The result set will
     * contain rows, each with the columns specified in [AffordanceTable.Columns].
     */
    object AffordanceTable {
        const val TABLE_NAME = "affordances"
        val URI: Uri = BASE_URI.buildUpon().path(TABLE_NAME).build()

        object Columns {
            /** String. Unique ID for this affordance. */
            const val ID = "id"
            /** String. User-visible name for this affordance. */
            const val NAME = "name"
            /**
             * Integer. Resource ID for the drawable to load for this affordance. This is a resource
             * ID from the system UI package.
             */
            const val ICON = "icon"
        }
    }

    /**
     * Table for selections.
     *
     * Selections are pairs of slot and affordance IDs.
     *
     * Supported operations:
     * - Insert - to insert an affordance and place it in a slot, insert values for the columns into
     * the [SelectionTable.URI] [Uri]. The maximum capacity rule is enforced by the system.
     * Selecting a new affordance for a slot that is already full will automatically remove the
     * oldest affordance from the slot.
     * - Query - to know which affordances are set on which slots, query the [SelectionTable.URI]
     * [Uri]. The result set will contain rows, each of which with the columns from
     * [SelectionTable.Columns].
     * - Delete - to unselect an affordance, removing it from a slot, delete from the
     * [SelectionTable.URI] [Uri], passing in values for each column.
     */
    object SelectionTable {
        const val TABLE_NAME = "selections"
        val URI: Uri = BASE_URI.buildUpon().path(TABLE_NAME).build()

        object Columns {
            /** String. Unique ID for the slot. */
            const val SLOT_ID = "slot_id"
            /** String. Unique ID for the selected affordance. */
            const val AFFORDANCE_ID = "affordance_id"
        }
    }

    /**
     * Table for flags.
     *
     * Flags are key-value pairs.
     *
     * Supported operations:
     * - Query - to know the values of flags, query the [FlagsTable.URI] [Uri]. The result set will
     * contain rows, each of which with the columns from [FlagsTable.Columns].
     */
    object FlagsTable {
        const val TABLE_NAME = "flags"
        val URI: Uri = BASE_URI.buildUpon().path(TABLE_NAME).build()

        /**
         * Flag denoting whether the customizable lock screen quick affordances feature is enabled.
         */
        const val FLAG_NAME_FEATURE_ENABLED = "is_feature_enabled"

        object Columns {
            /** String. Unique ID for the flag. */
            const val NAME = "name"
            /** Int. Value of the flag. `1` means `true` and `0` means `false`. */
            const val VALUE = "value"
        }
    }
}
