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

package com.android.systemui.keyguard

import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SystemUIAppComponentFactoryBase
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceSelectionManager
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.keyguard.data.content.KeyguardQuickAffordanceProviderContract as Contract
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceProviderTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: KeyguardQuickAffordanceProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = KeyguardQuickAffordanceProvider()
        val scope = CoroutineScope(IMMEDIATE)
        val selectionManager =
            KeyguardQuickAffordanceSelectionManager(
                context = context,
                userFileManager =
                    mock<UserFileManager>().apply {
                        whenever(
                                getSharedPreferences(
                                    anyString(),
                                    anyInt(),
                                    anyInt(),
                                )
                            )
                            .thenReturn(FakeSharedPreferences())
                    },
                userTracker = userTracker,
            )
        val quickAffordanceRepository =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = scope,
                selectionManager = selectionManager,
                configs =
                    setOf(
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_1,
                            pickerName = AFFORDANCE_1_NAME,
                            pickerIconResourceId = 1,
                        ),
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_2,
                            pickerName = AFFORDANCE_2_NAME,
                            pickerIconResourceId = 2,
                        ),
                    ),
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = scope,
                        backgroundDispatcher = IMMEDIATE,
                        secureSettings = FakeSettings(),
                        selectionsManager = selectionManager,
                    ),
            )
        underTest.interactor =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = FakeKeyguardRepository(),
                    ),
                registry = mock(),
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, true)
                    },
                repository = { quickAffordanceRepository },
            )

        underTest.attachInfoForTesting(
            context,
            ProviderInfo().apply { authority = Contract.AUTHORITY },
        )
        context.contentResolver.addProvider(Contract.AUTHORITY, underTest)
        context.testablePermissions.setPermission(
            Contract.PERMISSION,
            PackageManager.PERMISSION_GRANTED,
        )
    }

    @Test
    fun `onAttachInfo - reportsContext`() {
        val callback: SystemUIAppComponentFactoryBase.ContextAvailableCallback = mock()
        underTest.setContextAvailableCallback(callback)

        underTest.attachInfo(context, null)

        verify(callback).onContextAvailable(context)
    }

    @Test
    fun getType() {
        assertThat(underTest.getType(Contract.AffordanceTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}.${Contract.AffordanceTable.TABLE_NAME}"
            )
        assertThat(underTest.getType(Contract.SlotTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd.${Contract.AUTHORITY}.${Contract.SlotTable.TABLE_NAME}"
            )
        assertThat(underTest.getType(Contract.SelectionTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}.${Contract.SelectionTable.TABLE_NAME}"
            )
    }

    @Test
    fun `insert and query selection`() =
        runBlocking(IMMEDIATE) {
            val slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
            val affordanceId = AFFORDANCE_2
            val affordanceName = AFFORDANCE_2_NAME

            insertSelection(
                slotId = slotId,
                affordanceId = affordanceId,
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = slotId,
                            affordanceId = affordanceId,
                            affordanceName = affordanceName,
                        )
                    )
                )
        }

    @Test
    fun `query slots`() =
        runBlocking(IMMEDIATE) {
            assertThat(querySlots())
                .isEqualTo(
                    listOf(
                        Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            capacity = 1,
                        ),
                        Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                            capacity = 1,
                        ),
                    )
                )
        }

    @Test
    fun `query affordances`() =
        runBlocking(IMMEDIATE) {
            assertThat(queryAffordances())
                .isEqualTo(
                    listOf(
                        Affordance(
                            id = AFFORDANCE_1,
                            name = AFFORDANCE_1_NAME,
                            iconResourceId = 1,
                        ),
                        Affordance(
                            id = AFFORDANCE_2,
                            name = AFFORDANCE_2_NAME,
                            iconResourceId = 2,
                        ),
                    )
                )
        }

    @Test
    fun `delete and query selection`() =
        runBlocking(IMMEDIATE) {
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
            )
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
            )

            context.contentResolver.delete(
                Contract.SelectionTable.URI,
                "${Contract.SelectionTable.Columns.SLOT_ID} = ? AND" +
                    " ${Contract.SelectionTable.Columns.AFFORDANCE_ID} = ?",
                arrayOf(
                    KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                    AFFORDANCE_2,
                ),
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                            affordanceName = AFFORDANCE_1_NAME,
                        )
                    )
                )
        }

    @Test
    fun `delete all selections in a slot`() =
        runBlocking(IMMEDIATE) {
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
            )
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
            )

            context.contentResolver.delete(
                Contract.SelectionTable.URI,
                Contract.SelectionTable.Columns.SLOT_ID,
                arrayOf(
                    KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                ),
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                            affordanceName = AFFORDANCE_1_NAME,
                        )
                    )
                )
        }

    private fun insertSelection(
        slotId: String,
        affordanceId: String,
    ) {
        context.contentResolver.insert(
            Contract.SelectionTable.URI,
            ContentValues().apply {
                put(Contract.SelectionTable.Columns.SLOT_ID, slotId)
                put(Contract.SelectionTable.Columns.AFFORDANCE_ID, affordanceId)
            }
        )
    }

    private fun querySelections(): List<Selection> {
        return context.contentResolver
            .query(
                Contract.SelectionTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val slotIdColumnIndex =
                        cursor.getColumnIndex(Contract.SelectionTable.Columns.SLOT_ID)
                    val affordanceIdColumnIndex =
                        cursor.getColumnIndex(Contract.SelectionTable.Columns.AFFORDANCE_ID)
                    val affordanceNameColumnIndex =
                        cursor.getColumnIndex(Contract.SelectionTable.Columns.AFFORDANCE_NAME)
                    if (
                        slotIdColumnIndex == -1 ||
                            affordanceIdColumnIndex == -1 ||
                            affordanceNameColumnIndex == -1
                    ) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Selection(
                                slotId = cursor.getString(slotIdColumnIndex),
                                affordanceId = cursor.getString(affordanceIdColumnIndex),
                                affordanceName = cursor.getString(affordanceNameColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    private fun querySlots(): List<Slot> {
        return context.contentResolver
            .query(
                Contract.SlotTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val idColumnIndex = cursor.getColumnIndex(Contract.SlotTable.Columns.ID)
                    val capacityColumnIndex =
                        cursor.getColumnIndex(Contract.SlotTable.Columns.CAPACITY)
                    if (idColumnIndex == -1 || capacityColumnIndex == -1) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Slot(
                                id = cursor.getString(idColumnIndex),
                                capacity = cursor.getInt(capacityColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    private fun queryAffordances(): List<Affordance> {
        return context.contentResolver
            .query(
                Contract.AffordanceTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val idColumnIndex = cursor.getColumnIndex(Contract.AffordanceTable.Columns.ID)
                    val nameColumnIndex =
                        cursor.getColumnIndex(Contract.AffordanceTable.Columns.NAME)
                    val iconColumnIndex =
                        cursor.getColumnIndex(Contract.AffordanceTable.Columns.ICON)
                    if (idColumnIndex == -1 || nameColumnIndex == -1 || iconColumnIndex == -1) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Affordance(
                                id = cursor.getString(idColumnIndex),
                                name = cursor.getString(nameColumnIndex),
                                iconResourceId = cursor.getInt(iconColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    data class Slot(
        val id: String,
        val capacity: Int,
    )

    data class Affordance(
        val id: String,
        val name: String,
        val iconResourceId: Int,
    )

    data class Selection(
        val slotId: String,
        val affordanceId: String,
        val affordanceName: String,
    )

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val AFFORDANCE_1 = "affordance_1"
        private const val AFFORDANCE_2 = "affordance_2"
        private const val AFFORDANCE_1_NAME = "affordance_1_name"
        private const val AFFORDANCE_2_NAME = "affordance_2_name"
    }
}
