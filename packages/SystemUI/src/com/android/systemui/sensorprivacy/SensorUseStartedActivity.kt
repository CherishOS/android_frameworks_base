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

import android.app.KeyguardManager
import android.app.KeyguardManager.KeyguardDismissCallback
import android.content.DialogInterface
import android.content.Intent.EXTRA_PACKAGE_NAME
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.SensorPrivacyManager
import android.hardware.SensorPrivacyManager.EXTRA_ALL_SENSORS
import android.hardware.SensorPrivacyManager.EXTRA_SENSOR
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import com.android.internal.app.AlertActivity
import com.android.internal.widget.DialogTitle
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController

import javax.inject.Inject

/**
 * Dialog to be shown on top of apps that are attempting to use a sensor (e.g. microphone) which is
 * currently in "sensor privacy mode", aka. muted.
 *
 * <p>The dialog is started for the user the app is running for which might be a secondary users.
 */
class SensorUseStartedActivity @Inject constructor(
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val keyguardManager: KeyguardManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor
) : AlertActivity(), DialogInterface.OnClickListener {

    companion object {
        private val LOG_TAG = SensorUseStartedActivity::class.java.simpleName

        private const val SUPPRESS_REMINDERS_REMOVAL_DELAY_MILLIS = 2000L

        private const val CAMERA = SensorPrivacyManager.Sensors.CAMERA
        private const val MICROPHONE = SensorPrivacyManager.Sensors.MICROPHONE
        private const val ALL_SENSORS = Integer.MAX_VALUE
    }

    private var sensor = -1
    private lateinit var sensorUsePackageName: String
    private var unsuppressImmediately = false

    private lateinit var sensorPrivacyListener: IndividualSensorPrivacyController.Callback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)

        setFinishOnTouchOutside(false)

        setResult(RESULT_CANCELED)

        sensorUsePackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return

        if (intent.getBooleanExtra(EXTRA_ALL_SENSORS, false)) {
            sensor = ALL_SENSORS
            sensorPrivacyListener =
                    IndividualSensorPrivacyController.Callback { _, _ ->
                        if (!sensorPrivacyController.isSensorBlocked(MICROPHONE) &&
                                !sensorPrivacyController.isSensorBlocked(CAMERA)) {
                            dismiss()
                        }
                    }

            sensorPrivacyController.addCallback(sensorPrivacyListener)
            if (!sensorPrivacyController.isSensorBlocked(MICROPHONE) &&
                    !sensorPrivacyController.isSensorBlocked(CAMERA)) {
                finish()
                return
            }
        } else {
            sensor = intent.getIntExtra(EXTRA_SENSOR, -1).also {
                if (it == -1) {
                    finish()
                    return
                }
            }
            sensorPrivacyListener =
                    IndividualSensorPrivacyController.Callback {
                        whichSensor: Int, isBlocked: Boolean ->
                        if (whichSensor == sensor && !isBlocked) {
                            dismiss()
                        }
                    }
            sensorPrivacyController.addCallback(sensorPrivacyListener)

            sensorPrivacyController.addCallback { _, isBlocked ->
                if (!isBlocked) {
                    dismiss()
                }
            }
        }

        mAlertParams.apply {
            try {
                mCustomTitleView = mInflater.inflate(R.layout.sensor_use_started_title, null)
                mCustomTitleView.findViewById<DialogTitle>(R.id.sensor_use_started_title_message)!!
                        .setText(when (sensor) {
                            MICROPHONE ->
                                R.string.sensor_privacy_start_use_mic_dialog_title
                            CAMERA ->
                                R.string.sensor_privacy_start_use_camera_dialog_title
                            ALL_SENSORS ->
                                R.string.sensor_privacy_start_use_mic_camera_dialog_title
                            else -> Resources.ID_NULL
                        })

                mCustomTitleView.findViewById<ImageView>(R.id.sensor_use_microphone_icon)!!
                        .visibility = if (sensor == MICROPHONE || sensor == ALL_SENSORS) {
                    VISIBLE
                } else {
                    GONE
                }
                mCustomTitleView.findViewById<ImageView>(R.id.sensor_use_camera_icon)!!
                        .visibility = if (sensor == CAMERA || sensor == ALL_SENSORS) {
                    VISIBLE
                } else {
                    GONE
                }

                mMessage = Html.fromHtml(getString(when (sensor) {
                    MICROPHONE ->
                        R.string.sensor_privacy_start_use_mic_dialog_content
                    CAMERA ->
                        R.string.sensor_privacy_start_use_camera_dialog_content
                    ALL_SENSORS ->
                        R.string.sensor_privacy_start_use_mic_camera_dialog_content
                    else -> Resources.ID_NULL
                }, packageManager.getApplicationInfo(sensorUsePackageName, 0)
                        .loadLabel(packageManager)), 0)
            } catch (e: PackageManager.NameNotFoundException) {
                finish()
                return
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

        sensorPrivacyController.suppressSensorPrivacyReminders(sensorUsePackageName, true)
        unsuppressImmediately = false
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            BUTTON_POSITIVE -> {
                if (keyguardUpdateMonitor.getUserHasTrust(userId)) {
                    keyguardManager
                            .requestDismissKeyguard(this, object : KeyguardDismissCallback() {
                        override fun onDismissError() {
                            Log.e(LOG_TAG, "Cannot dismiss keyguard")
                        }

                        override fun onDismissSucceeded() {
                            disableSensorPrivacy()
                        }
                    })
                } else {
                    disableSensorPrivacy()
                }
            }
            BUTTON_NEGATIVE -> {
                unsuppressImmediately = false
            }
        }

        dismiss()
    }

    override fun onStop() {
        super.onStop()

        if (unsuppressImmediately) {
            sensorPrivacyController
                    .suppressSensorPrivacyReminders(sensorUsePackageName, false)
        } else {
            Handler(mainLooper).postDelayed({
                sensorPrivacyController
                        .suppressSensorPrivacyReminders(sensorUsePackageName, false)
            }, SUPPRESS_REMINDERS_REMOVAL_DELAY_MILLIS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorPrivacyController.removeCallback(sensorPrivacyListener)
    }

    override fun onBackPressed() {
        // do not allow backing out
    }

    private fun disableSensorPrivacy() {
        sensorPrivacyController.setSensorBlocked(sensor, false)
        unsuppressImmediately = true
        setResult(RESULT_OK)
    }
}