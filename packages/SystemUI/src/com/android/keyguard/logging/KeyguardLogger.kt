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

package com.android.keyguard.logging

import com.android.systemui.log.dagger.KeyguardLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.plugins.log.LogLevel.ERROR
import com.android.systemui.plugins.log.LogLevel.INFO
import com.android.systemui.plugins.log.LogLevel.VERBOSE
import com.android.systemui.plugins.log.LogLevel.WARNING
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "KeyguardLog"

/**
 * Generic logger for keyguard that's wrapping [LogBuffer]. This class should be used for adding
 * temporary logs or logs for smaller classes when creating whole new [LogBuffer] wrapper might be
 * an overkill.
 */
class KeyguardLogger @Inject constructor(@KeyguardLog private val buffer: LogBuffer) {
    fun d(@CompileTimeConstant msg: String) = buffer.log(TAG, DEBUG, msg)

    fun e(@CompileTimeConstant msg: String) = buffer.log(TAG, ERROR, msg)

    fun v(@CompileTimeConstant msg: String) = buffer.log(TAG, VERBOSE, msg)

    fun w(@CompileTimeConstant msg: String) = buffer.log(TAG, WARNING, msg)

    fun logException(ex: Exception, @CompileTimeConstant logMsg: String) {
        buffer.log(TAG, ERROR, {}, { logMsg }, exception = ex)
    }

    fun v(msg: String, arg: Any) {
        buffer.log(TAG, VERBOSE, { str1 = arg.toString() }, { "$msg: $str1" })
    }

    fun i(msg: String, arg: Any) {
        buffer.log(TAG, INFO, { str1 = arg.toString() }, { "$msg: $str1" })
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarCalculatedAlpha(alpha: Float) {
        buffer.log(TAG, DEBUG, { double1 = alpha.toDouble() }, { "Calculated new alpha: $double1" })
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarExplicitAlpha(alpha: Float) {
        buffer.log(
            TAG,
            DEBUG,
            { double1 = alpha.toDouble() },
            { "new mExplicitAlpha value: $double1" }
        )
    }

    // TODO: remove after b/237743330 is fixed
    fun logStatusBarAlphaVisibility(visibility: Int, alpha: Float, state: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                int1 = visibility
                double1 = alpha.toDouble()
                str1 = state
            },
            { "changing visibility to $int1 with alpha $double1 in state: $str1" }
        )
    }

    @JvmOverloads
    fun logBiometricMessage(
        @CompileTimeConstant context: String,
        msgId: Int? = null,
        msg: String? = null
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = context
                str2 = "$msgId"
                str3 = msg
            },
            { "$str1 msgId: $str2 msg: $str3" }
        )
    }
}
