/*
 *  Copyright (C) 2021 CorvusOS
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

package com.android.systemui.corvus.qsweather;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.omni.OmniJawsClient;

import java.util.Arrays;

public class QsWeatherText extends TextView implements
        OmniJawsClient.OmniJawsObserver {

    private static final String TAG = QsWeatherText.class.getSimpleName();

    private static final boolean DEBUG = false;

    private Context mContext;

    private int mQsWeatherEnabled;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mEnabled;

    Handler mHandler;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.QS_SHOW_WEATHER_TEMP), false, this,
                    UserHandle.USER_ALL);
            updateSettings(false);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings(true);
        }
    }

    public QsWeatherText(Context context) {
        this(context, null);

    }

    public QsWeatherText(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QsWeatherText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mContext = context;
        mHandler = new Handler();
        mWeatherClient = new OmniJawsClient(mContext);
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        mWeatherClient.addObserver(this);
        queryAndUpdateWeather();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
    }

    public void updateSettings(boolean onChange) {
        ContentResolver resolver = mContext.getContentResolver();
        mQsWeatherEnabled = Settings.System.getIntForUser(
                resolver, Settings.System.QS_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        if ((mQsWeatherEnabled != 0 && mQsWeatherEnabled != 5)) {
            mWeatherClient.setOmniJawsEnabled(true);
            queryAndUpdateWeather();
        } else {
            setVisibility(View.GONE);
        }

        if (onChange && mQsWeatherEnabled == 0) {
            // Disable OmniJaws if tile isn't used either
            String[] tiles = Settings.Secure.getStringForUser(resolver,
                    Settings.Secure.QS_TILES, UserHandle.USER_CURRENT).split(",");
            boolean weatherTileEnabled = Arrays.asList(tiles).contains("weather");
            Log.d(TAG, "Weather tile enabled " + weatherTileEnabled);
            if (!weatherTileEnabled) {
                mWeatherClient.setOmniJawsEnabled(false);
            }
        }
    }

    private void queryAndUpdateWeather() {
        try {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather " + mEnabled);
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData != null) {
                    if ((mQsWeatherEnabled != 0 || mQsWeatherEnabled != 5)) {
                        if (mQsWeatherEnabled == 2 || mQsWeatherEnabled == 4) {
                            setText(mWeatherData.temp);
                        } else {
                            setText(mWeatherData.temp + mWeatherData.tempUnits);
                        }
                        if (mQsWeatherEnabled != 0 && mQsWeatherEnabled != 5) {
                            setVisibility(View.VISIBLE);
                        }
                    }
                } else {
                    setVisibility(View.GONE);
                }
            } else {
                setVisibility(View.GONE);
            }
        } catch(Exception e) {
            // Do nothing
        }
    }
}
