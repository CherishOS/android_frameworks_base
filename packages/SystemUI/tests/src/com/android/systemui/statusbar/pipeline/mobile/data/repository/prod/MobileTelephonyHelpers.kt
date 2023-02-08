/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.google.common.truth.Truth.assertThat
import org.mockito.Mockito.verify

/** Helper methods for telephony-related callbacks for mobile tests. */
object MobileTelephonyHelpers {
    fun getTelephonyCallbacks(mockTelephonyManager: TelephonyManager): List<TelephonyCallback> {
        val callbackCaptor = argumentCaptor<TelephonyCallback>()
        verify(mockTelephonyManager).registerTelephonyCallback(any(), callbackCaptor.capture())
        return callbackCaptor.allValues
    }

    inline fun <reified T> getTelephonyCallbackForType(mockTelephonyManager: TelephonyManager): T {
        val cbs = getTelephonyCallbacks(mockTelephonyManager).filterIsInstance<T>()
        assertThat(cbs.size).isEqualTo(1)
        return cbs[0]
    }
}
