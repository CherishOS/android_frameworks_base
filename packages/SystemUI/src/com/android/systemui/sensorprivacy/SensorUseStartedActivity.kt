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

package com.android.systemui.sensorprivacy

import android.app.AppOpsManager
import android.content.DialogInterface
import android.content.Intent.EXTRA_PACKAGE_NAME
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.*
import android.os.Bundle
import android.text.Html
import com.android.internal.app.AlertActivity
import com.android.systemui.R

/**
 * Dialog to be shown on top of apps that are attempting to use a sensor (e.g. microphone) which is
 * currently in "sensor privacy mode", aka. muted.
 *
 * <p>The dialog is started for the user the app is running for which might be a secondary users.
 */
class SensorUseStartedActivity : AlertActivity(), DialogInterface.OnClickListener {
    private var sensor = -1
    private lateinit var sensorUsePackageName: String

    private lateinit var sensorPrivacyManager: SensorPrivacyManager
    private lateinit var appOpsManager: AppOpsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)
        sensorPrivacyManager = getSystemService(SensorPrivacyManager::class.java)!!
        appOpsManager = getSystemService(AppOpsManager::class.java)!!

        sensorUsePackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        sensor = intent.getIntExtra(EXTRA_SENSOR, -1).also {
            if (it == -1) {
                finish()
                return
            }
        }

        sensorPrivacyManager.addSensorPrivacyListener(sensor) { isBlocked ->
            if (!isBlocked) {
                dismiss()
            }
        }
        if (!sensorPrivacyManager.isIndividualSensorPrivacyEnabled(sensor)) {
            finish()
            return
        }

        mAlertParams.apply {
            try {
                mMessage = Html.fromHtml(getString(when (sensor) {
                    INDIVIDUAL_SENSOR_MICROPHONE ->
                        R.string.sensor_privacy_start_use_mic_dialog_content
                    INDIVIDUAL_SENSOR_CAMERA ->
                        R.string.sensor_privacy_start_use_camera_dialog_content
                    else -> Resources.ID_NULL
                }, packageManager.getApplicationInfo(sensorUsePackageName, 0)
                        .loadLabel(packageManager)), 0)
            } catch (e: PackageManager.NameNotFoundException) {
                finish()
                return
            }

            mIconId = when (sensor) {
                INDIVIDUAL_SENSOR_MICROPHONE ->
                    com.android.internal.R.drawable.perm_group_microphone
                INDIVIDUAL_SENSOR_CAMERA -> com.android.internal.R.drawable.perm_group_camera
                else -> Resources.ID_NULL
            }

            mPositiveButtonText = getString(
                    com.android.internal.R.string.sensor_privacy_start_use_dialog_turn_on_button)
            mNegativeButtonText = getString(android.R.string.cancel)
            mPositiveButtonListener = this@SensorUseStartedActivity
            mNegativeButtonListener = this@SensorUseStartedActivity
        }

        setupAlert()
    }

    override fun onStart() {
        super.onStart()

        sensorPrivacyManager.suppressIndividualSensorPrivacyReminders(sensorUsePackageName, true)
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            BUTTON_POSITIVE -> {
                sensorPrivacyManager.setIndividualSensorPrivacyForProfileGroup(sensor, false)
                setResult(RESULT_OK)
            }
        }

        dismiss()
    }

    override fun onStop() {
        super.onDestroy()

        sensorPrivacyManager.suppressIndividualSensorPrivacyReminders(sensorUsePackageName, false)
    }
}