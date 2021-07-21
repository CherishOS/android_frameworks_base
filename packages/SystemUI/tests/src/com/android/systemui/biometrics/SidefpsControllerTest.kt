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

package com.android.systemui.biometrics

import android.hardware.biometrics.SensorProperties
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.Display
import android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
import android.view.DisplayInfo
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

private const val DISPLAY_ID = 2
private const val SENSOR_ID = 1

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SidefpsControllerTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock
    lateinit var layoutInflater: LayoutInflater
    @Mock
    lateinit var fingerprintManager: FingerprintManager
    @Mock
    lateinit var windowManager: WindowManager
    @Mock
    lateinit var sidefpsView: SidefpsView
    @Mock
    lateinit var displayManager: DisplayManager
    @Mock
    lateinit var handler: Handler

    private val executor = FakeExecutor(FakeSystemClock())
    private lateinit var overlayController: ISidefpsController
    private lateinit var sideFpsController: SidefpsController

    @Before
    fun setup() {
        `when`(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sidefpsView)
        `when`(fingerprintManager.sensorPropertiesInternal).thenReturn(
                listOf(
                        FingerprintSensorPropertiesInternal(
                                SENSOR_ID,
                                SensorProperties.STRENGTH_STRONG,
                                5 /* maxEnrollmentsPerUser */,
                                listOf() /* componentInfo */,
                                FingerprintSensorProperties.TYPE_POWER_BUTTON,
                                true /* resetLockoutRequiresHardwareAuthToken */
                        )
                )
        )
        `when`(windowManager.defaultDisplay).thenReturn(
                Display(
                        DisplayManagerGlobal.getInstance(),
                        DISPLAY_ID,
                        DisplayInfo(),
                        DEFAULT_DISPLAY_ADJUSTMENTS
                )
        )

        sideFpsController = SidefpsController(
                mContext, layoutInflater, fingerprintManager, windowManager, executor,
                displayManager, handler
        )

        overlayController = ArgumentCaptor.forClass(ISidefpsController::class.java).apply {
            verify(fingerprintManager).setSidefpsController(capture())
        }.value
    }

    @Test
    fun testSubscribesToOrientationChangesWhenShowingOverlay() {
        overlayController.show()
        executor.runAllReady()

        verify(displayManager).registerDisplayListener(any(), eq(handler))

        overlayController.hide()
        executor.runAllReady()
        verify(displayManager).unregisterDisplayListener(any())
    }
}
