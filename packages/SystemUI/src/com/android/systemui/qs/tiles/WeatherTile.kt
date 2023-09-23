/*
 * Copyright (C) 2017 The OmniROM project
 * Copyright (C) 2022-2023 crDroid Android project
 * Copyright (C) 2023 the risingOS Android project
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

package com.android.systemui.qs.tiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.util.Log
import android.view.View
import androidx.annotation.Nullable
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.cherish.OmniJawsClient
import com.android.internal.util.cherish.CherishUtils
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import javax.inject.Inject

class WeatherTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    private val mActivityStarter: ActivityStarter,
    qsLogger: QSLogger
) : QSTileImpl<BooleanState>(host, backgroundLooper, mainHandler, falsingManager, metricsLogger, statusBarStateController, mActivityStarter, qsLogger),
    OmniJawsClient.OmniJawsObserver {

    companion object {
        const val TILE_SPEC = "weather"
        private const val TAG = "WeatherTile"
        private const val DEBUG = false
        private val ALTERNATIVE_WEATHER_APPS = arrayOf(
            "cz.martykan.forecastie",
            "com.accuweather.android",
            "com.wunderground.android.weather",
            "com.samruston.weather",
            "jp.miyavi.androiod.gnws"
        )
    }

    private var mWeatherClient: OmniJawsClient = OmniJawsClient(mContext)
    private var mWeatherImage: Drawable? = null
    private var mWeatherData: OmniJawsClient.WeatherInfo? = null
    private var mEnabled: Boolean = mWeatherClient.isOmniJawsEnabled()

    override fun getMetricsCategory(): Int {
        return MetricsEvent.CHERISH_SETTINGS;
    }

    override fun newTileState(): BooleanState {
        return BooleanState()
    }

    override fun handleSetListening(listening: Boolean) {
        if (mWeatherClient == null) {
            return
        }
        if (DEBUG) Log.d(TAG, "setListening $listening")
        mEnabled = mWeatherClient.isOmniJawsEnabled()

        if (listening) {
            mWeatherClient.addObserver(this)
            queryAndUpdateWeather()
        } else {
            mWeatherClient.removeObserver(this)
        }
    }

    override fun weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated")
        queryAndUpdateWeather()
    }

    override fun weatherError(errorReason: Int) {
        if (DEBUG) Log.d(TAG, "weatherError $errorReason")
        if (errorReason != OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherData = null
            refreshState()
        }
    }

    override fun handleDestroy() {
        // make sure we dont left one
        mWeatherClient.removeObserver(this)
        super.handleDestroy()
    }

    override fun isAvailable(): Boolean {
        return mWeatherClient.isOmniJawsServiceInstalled()
    }

    override fun handleClick(@Nullable view: View?) {
        if (DEBUG) Log.d(TAG, "handleClick")
        if (!mState.value || mWeatherData == null) {
            mActivityStarter.postStartActivityDismissingKeyguard(mWeatherClient.getSettingsIntent(), 0)
        } else {
            val pm = mContext.packageManager
            for (app in ALTERNATIVE_WEATHER_APPS) {
                if (CherishUtils.isPackageInstalled(mContext, app)) {
                    val intent = pm.getLaunchIntentForPackage(app)
                    if (intent != null) {
                        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0)
                    }
                }
            }
            if (CherishUtils.isPackageInstalled(mContext, "com.google.android.googlequicksearchbox")) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("dynact://velour/weather/ProxyActivity")
                intent.component = ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"
                )
                mActivityStarter.postStartActivityDismissingKeyguard(intent, 0)
            }
        }
        mEnabled = mWeatherClient.isOmniJawsEnabled()
        refreshState()
    }

    override fun getLongClickIntent(): Intent {
        if (DEBUG) Log.d(TAG, "getLongClickIntent")
        return mWeatherClient.getSettingsIntent()
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        if (DEBUG) Log.d(TAG, "handleUpdateState $mEnabled")
        state.value = mEnabled
        state.state = if (state.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.icon = ResourceIcon.get(R.drawable.ic_qs_weather)
        state.label = mContext.resources.getString(R.string.omnijaws_label_default)
        state.secondaryLabel = mContext.resources.getString(R.string.omnijaws_service_unknown)
        if (mEnabled) {
            if (mWeatherData == null || mWeatherImage == null) {
                state.label = mContext.resources.getString(R.string.omnijaws_label_default)
                state.secondaryLabel = mContext.resources.getString(R.string.omnijaws_service_error)
            } else {
                state.icon = DrawableIcon(mWeatherImage)
                state.label = mWeatherData!!.city
                state.secondaryLabel = mWeatherData!!.temp + mWeatherData!!.tempUnits
            }
        }
    }

    override fun getTileLabel(): CharSequence {
        return mContext.resources.getString(R.string.omnijaws_label_default)
    }

    private fun queryAndUpdateWeather() {
        if (DEBUG) Log.d(TAG, "queryAndUpdateWeather $mEnabled")
        try {
            mWeatherData = null
            if (mEnabled) {
                mWeatherClient.queryWeather()
                mWeatherData = mWeatherClient.getWeatherInfo()
                if (mWeatherData != null) {
                    mWeatherImage = mWeatherClient.getWeatherConditionImage(mWeatherData!!.conditionCode)
                    mWeatherImage = mWeatherImage!!.mutate()
                }
            }
        } catch (e: Exception) {
            // Do nothing
        }
        refreshState()
    }
}
