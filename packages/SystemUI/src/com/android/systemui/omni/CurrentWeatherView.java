/*
* Copyright (C) 2018 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.omni;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import androidx.core.graphics.ColorUtils;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.settingslib.Utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class CurrentWeatherView extends FrameLayout implements OmniJawsClient.OmniJawsObserver {

    static final String TAG = "SystemUI:CurrentWeatherView";
    static final boolean DEBUG = false;

    private ImageView mCurrentImage;
    private OmniJawsClient mWeatherClient;
    private TextView mLeftText;
    private TextView mRightText;
    private int mTextColor;
    private float mDarkAmount;
    private boolean mUpdatesEnabled;
    private SettingsObserver mSettingsObserver;
    private int mRightTextColor;
    private int mLeftTextColor;
    private int mCurrentImageColor;
    private int omniRightTextFont;
    private int omniLeftTextFont;
    private LinearLayout mLayout;

    public CurrentWeatherView(Context context) {
        this(context, null);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void enableUpdates() {
        if (mUpdatesEnabled) {
            return;
        }
        if (DEBUG) Log.d(TAG, "enableUpdates");
        mWeatherClient = new OmniJawsClient(getContext(), false);
        if (mWeatherClient.isOmniJawsEnabled()) {
            setVisibility(View.VISIBLE);
            mWeatherClient.addSettingsObserver();
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
            mUpdatesEnabled = true;
        }
    }

    public void disableUpdates() {
        if (!mUpdatesEnabled) {
            return;
        }
        if (DEBUG) Log.d(TAG, "disableUpdates");
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
            mWeatherClient.cleanupObserver();
            mUpdatesEnabled = false;
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLayout = findViewById(R.id.current);
        mCurrentImage  = (ImageView) findViewById(R.id.current_image);
        mLeftText = (TextView) findViewById(R.id.left_text);
        mRightText = (TextView) findViewById(R.id.right_text);
        mTextColor = mLeftText.getCurrentTextColor();
        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();
        mSettingsObserver.update();
    }

    private void updateWeatherData(OmniJawsClient.WeatherInfo weatherData) {
        boolean showTemp = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCKSCREEN_WEATHER_SHOW_TEMP, 1, UserHandle.USER_CURRENT) == 1;
        boolean showCity = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCKSCREEN_WEATHER_SHOW_CITY, 1, UserHandle.USER_CURRENT) == 1;
        boolean showImage = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCKSCREEN_WEATHER_SHOW_IMAGE, 1, UserHandle.USER_CURRENT) == 1;
        mRightTextColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCK_SCREEN_WEATHER_TEMP_COLOR, 0xFFFFFFFF, UserHandle.USER_CURRENT);
        omniRightTextFont = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCK_WEATHER_TEMP_FONTS, 27, UserHandle.USER_CURRENT);
        mLeftTextColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCK_SCREEN_WEATHER_CITY_COLOR, 0xFFFFFFFF, UserHandle.USER_CURRENT);
        omniLeftTextFont = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCK_WEATHER_CITY_FONTS, 27, UserHandle.USER_CURRENT);
        mCurrentImageColor = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.LOCK_SCREEN_WEATHER_ICON_COLOR, 0xFFFFFFFF, UserHandle.USER_CURRENT);
        if (DEBUG) Log.d(TAG, "updateWeatherData");

        if (!mWeatherClient.isOmniJawsEnabled() || weatherData == null) {
            setErrorView();
            return;
        }
        Drawable d = mWeatherClient.getWeatherConditionImage(weatherData.conditionCode);
        d = d.mutate();
        updateTint(d);
        if (showTemp) {
            mRightText.setText(weatherData.temp + " " + weatherData.tempUnits);
            mRightText.setTextColor(mRightTextColor);
        } else {
            mRightText.setText("");
        }
        if (showCity) {
            mLeftText.setText(weatherData.city);
            mLeftText.setTextColor(mLeftTextColor);
        } else {
            mLeftText.setText("");
        }
        if (showImage) {
            mCurrentImage.setImageDrawable(d);
            mCurrentImage.setColorFilter(mCurrentImageColor);
        } else {
            mCurrentImage.setImageResource(android.R.color.transparent);
        }
        if (omniLeftTextFont == 0) {
            mLeftText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 1) {
            mLeftText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (omniLeftTextFont == 2) {
            mLeftText.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 3) {
            mLeftText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (omniLeftTextFont == 4) {
            mLeftText.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 5) {
            mLeftText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 6) {
            mLeftText.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 7) {
            mLeftText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 8) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 9) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 10) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (omniLeftTextFont == 11) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (omniLeftTextFont == 12) {
            mLeftText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 13) {
            mLeftText.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 14) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 15) {
            mLeftText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 16) {
            mLeftText.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 17) {
            mLeftText.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 18) {
            mLeftText.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 19) {
            mLeftText.setTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (omniLeftTextFont == 20) {
            mLeftText.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 21) {
            mLeftText.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 22) {
            mLeftText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (omniLeftTextFont == 23) {
            mLeftText.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (omniLeftTextFont == 24) {
            mLeftText.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
        if (omniLeftTextFont == 25) {
            mLeftText.setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 26) {
            mLeftText.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 27) {
            mLeftText.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 28) {
            mLeftText.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 29) {
            mLeftText.setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
        }
        if (omniLeftTextFont == 30) {
            mLeftText.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 0) {
            mRightText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (omniRightTextFont == 1) {
            mRightText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (omniRightTextFont == 2) {
            mRightText.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (omniRightTextFont == 3) {
            mRightText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (omniRightTextFont == 4) {
            mRightText.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (omniRightTextFont == 5) {
            mRightText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (omniRightTextFont == 6) {
            mRightText.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (omniRightTextFont == 7) {
            mRightText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (omniRightTextFont == 8) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (omniRightTextFont == 9) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (omniRightTextFont == 10) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (omniRightTextFont == 11) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (omniRightTextFont == 12) {
            mRightText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (omniRightTextFont == 13) {
            mRightText.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (omniRightTextFont == 14) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (omniRightTextFont == 15) {
            mRightText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (omniRightTextFont == 16) {
            mRightText.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (omniRightTextFont == 17) {
            mRightText.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (omniRightTextFont == 18) {
            mRightText.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (omniRightTextFont == 19) {
            mRightText.setTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (omniRightTextFont == 20) {
            mRightText.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (omniRightTextFont == 21) {
            mRightText.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (omniRightTextFont == 22) {
            mRightText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (omniRightTextFont == 23) {
            mRightText.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (omniRightTextFont == 24) {
            mRightText.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
        if (omniRightTextFont == 25) {
            mRightText.setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 26) {
            mRightText.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 27) {
            mRightText.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 28) {
            mRightText.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 29) {
            mRightText.setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
        }
        if (omniRightTextFont == 30) {
            mRightText.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
        }
    }

    private int getTintColor() {
        return Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
    }

    private void setErrorView() {
        Drawable d = mContext.getResources().getDrawable(R.drawable.ic_qs_weather_default_off_white);
        updateTint(d);
        mCurrentImage.setImageDrawable(d);
        mLeftText.setText("");
        mRightText.setText("");
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
        // since this is shown in ambient and lock screen
        // it would look bad to show every error since the 
        // screen-on revovery of the service had no chance
        // to run fast enough
        // so only show the disabled state
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            setErrorView();
        }
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    @Override
    public void updateSettings() {
        if (DEBUG) Log.d(TAG, "updateSettings");
        OmniJawsClient.WeatherInfo weatherData = mWeatherClient.getWeatherInfo();
        updateWeatherData(weatherData);
    }

    private void queryAndUpdateWeather() {
        if (mWeatherClient != null) {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather");
            mWeatherClient.queryWeather();
            OmniJawsClient.WeatherInfo weatherData = mWeatherClient.getWeatherInfo();
            updateWeatherData(weatherData);
        }
    }

    public void blendARGB(float darkAmount) {
        mDarkAmount = darkAmount;
        mLeftText.setTextColor(ColorUtils.blendARGB(mTextColor, Color.WHITE, darkAmount));
        mRightText.setTextColor(ColorUtils.blendARGB(mTextColor, Color.WHITE, darkAmount));

        if (mWeatherClient != null) {
            // update image with correct tint
            OmniJawsClient.WeatherInfo weatherData = mWeatherClient.getWeatherInfo();
            updateWeatherData(weatherData);
        }
    }

    private void updateTint(Drawable d) {
        if (mDarkAmount == 1) {
            mCurrentImage.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        } else {
            mCurrentImage.setImageTintList((d instanceof VectorDrawable) ? ColorStateList.valueOf(getTintColor()) : null);
        }
    }

    public void onDensityOrFontScaleChanged() {
        mLeftText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        mRightText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        mCurrentImage.getLayoutParams().height =
                getResources().getDimensionPixelSize(R.dimen.current_weather_image_size);
        mCurrentImage.getLayoutParams().width =
                getResources().getDimensionPixelSize(R.dimen.current_weather_image_size);
    }

    public void setViewBackground(Drawable drawRes) {
        setViewBackground(drawRes, 255);
    }

    public void setViewBackground(Drawable drawRes, int bgAlpha) {
        mLayout.setBackground(drawRes);
        mLayout.getBackground().setAlpha(bgAlpha);
    }

    public void setViewBackgroundResource(int drawRes) {
        mLayout.setBackgroundResource(drawRes);
    }

    public void setViewPadding(int left, int top, int right, int bottom) {
        mLayout.setPadding(left,top,right,bottom);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_SHOW_TEMP),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_SHOW_CITY),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_SHOW_IMAGE),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_WEATHER_TEMP_COLOR),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_WEATHER_CITY_COLOR),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_SCREEN_WEATHER_ICON_COLOR),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_WEATHER_CITY_FONTS),
                    false, this, UserHandle.USER_ALL);
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_WEATHER_TEMP_FONTS),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            queryAndUpdateWeather();
        }
    }
}
