/*
 * Copyright (C) 2023 crDroid Android Project
 * Copyright (C) 2023 the risingOS Android Project
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
package com.android.systemui.cherish

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.util.cherish.OmniJawsClient
import com.android.systemui.R

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.HashMap

class CurrentWeatherView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : FrameLayout(context, attrs, defStyle), OmniJawsClient.OmniJawsObserver {

    companion object {
        const val TAG = "SystemUI:CurrentWeatherView"
    }

    private var mCurrentImage: ImageView? = null
    private var mWeatherClient: OmniJawsClient? = OmniJawsClient(context)
    private var mWeatherInfo: OmniJawsClient.WeatherInfo? = null
    private var mRightText: TextView? = null

    private var mSettingsObserver: SettingsObserver? = null
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private var mShowWeatherCondition = false
    private var mShowWeatherLocation = false

    fun enableUpdates() {
        mWeatherClient?.let {
            it.addObserver(this)
            queryAndUpdateWeather()
        }
    }

    fun disableUpdates() {
        mWeatherClient?.removeObserver(this)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mCurrentImage = findViewById(R.id.current_image)
        mRightText = findViewById(R.id.right_text)
        if (mSettingsObserver == null) {
            mSettingsObserver = SettingsObserver(Handler())
            mSettingsObserver?.observe()
        }
    }

    private fun setErrorView() {
        mCurrentImage?.setImageDrawable(null)
        mRightText?.text = ""
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null
            setErrorView()
        }
    }

    override fun weatherUpdated() {
        queryAndUpdateWeather()
    }

    override fun updateSettings() {
        queryAndUpdateWeather()
    }

    private fun queryAndUpdateWeather() {
        executor.execute {
            try {
                mWeatherClient?.let { client ->
                    if (!client.isOmniJawsEnabled()) {
                        return
                    }
                    client.queryWeather()
                    mWeatherInfo = client.weatherInfo
                    mWeatherInfo?.let { info ->
                        val d = client.getWeatherConditionImage(info.conditionCode)
                        mCurrentImage?.setImageDrawable(d)

                        val weatherConditions = mapOf(
                            "clouds" to context.getString(R.string.cloudy),
                            "rain" to context.getString(R.string.rainy),
                            "clear" to context.getString(R.string.sunny),
                            "storm" to context.getString(R.string.stormy),
                            "snow" to context.getString(R.string.snowy),
                            "wind" to context.getString(R.string.windy),
                            "mist" to context.getString(R.string.misty)
                        )

                        var formattedCondition = info.condition.toLowerCase()
                        for ((key, value) in weatherConditions) {
                            if (formattedCondition.contains(key)) {
                                formattedCondition = value
                                break
                            }
                        }

                        val temperature = info.temp.toDouble()
                        val units = info.tempUnits
                        var tempC: Int
                        var tempF: Int
                        if (units == "°F") {
                            tempC = ((temperature - 32) * 5.0 / 9.0).toInt()
                            tempF = temperature.toInt()
                        } else {
                            tempC = temperature.toInt()
                            tempF = (temperature * 9.0 / 5.0 + 32).toInt()
                        }

                        val weatherTemp = "%d%s \u2022 Today %d° / %d°".format(
                            if (units == "°F") tempF else tempC, 
                            units, tempC, tempF
                        ) + if (mShowWeatherLocation) " \u2022 " + info.city else "" +
                                if (mShowWeatherCondition) " \u2022 " + formattedCondition else ""

                        mRightText?.text = weatherTemp
                    }
                }
            } catch (e: Exception) {
                // Do nothing
            }
        }
    }

    inner class SettingsObserver(handler: Handler) : ContentObserver(handler) {

        fun observe() {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.LOCKSCREEN_WEATHER_LOCATION), false, this,
                UserHandle.USER_ALL)
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.LOCKSCREEN_WEATHER_CONDITION), false, this,
                UserHandle.USER_ALL)
            updateWeatherSettings()
        }

        fun unobserve() {
            context.contentResolver.unregisterContentObserver(this)
        }

        fun updateWeatherSettings() {
            mShowWeatherLocation = Settings.System.getIntForUser(context.contentResolver,
                Settings.System.LOCKSCREEN_WEATHER_LOCATION,
                0, UserHandle.USER_CURRENT) != 0
            mShowWeatherCondition = Settings.System.getIntForUser(context.contentResolver,
                Settings.System.LOCKSCREEN_WEATHER_CONDITION,
                1, UserHandle.USER_CURRENT) != 0
        }

        override fun onChange(selfChange: Boolean) {
            updateWeatherSettings()
        }
    }
}
