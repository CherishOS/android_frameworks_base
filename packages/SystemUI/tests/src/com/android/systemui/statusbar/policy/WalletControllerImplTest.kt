/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.service.quickaccesswallet.QuickAccessWalletClient
import android.testing.AndroidTestingRunner

import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl.Companion.QS_PRIORITY_POSITION

import com.google.common.truth.Truth.assertThat

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidTestingRunner::class)
class WalletControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var quickAccessWalletClient: QuickAccessWalletClient

    private lateinit var controller: WalletController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        controller = WalletControllerImpl(quickAccessWalletClient)
    }

    @Test
    fun testResultIsNullWhenNoServiceAvailable() {
        `when`(quickAccessWalletClient.isWalletServiceAvailable()).thenReturn(false)
        assertThat(controller.getWalletPosition()).isNull()
    }

    @Test
    fun testResultIsIntWhenServiceAvailable() {
        `when`(quickAccessWalletClient.isWalletServiceAvailable()).thenReturn(true)
        assertThat(controller.getWalletPosition()).isEqualTo(QS_PRIORITY_POSITION)
    }
}
