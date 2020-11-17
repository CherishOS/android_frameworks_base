/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.app.WallpaperManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextClock;

import com.android.internal.colorextraction.ColorExtractor;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.util.TimeZone;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class SquaryClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    private final SysuiColorExtractor mColorExtractor;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Root view of clock.
     */
    private ClockLayout mView;

    /**
     * Text clock in preview view hierarchy.
     */
    private TextClock mHour;
    private TextClock mMinutes;

    /**
     * Textclock backgrounds
     */
    private ImageView mHourBkg;
    private ImageView mMinBkg;

    /**
     * Configuration controller for theme changes
     */
    private ConfigurationController mConfigurationController;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public SquaryClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
        mConfigurationController = Dependency.get(ConfigurationController.class);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.digital_clock_custom_squaryclock, null);
        mHour = mView.findViewById(R.id.hours);
        mMinutes = mView.findViewById(R.id.minutes);
        mHourBkg = mView.findViewById(R.id.hr_bkg);
        mMinBkg = mView.findViewById(R.id.min_bkg);
        mHour.setFormat12Hour(Html.fromHtml("hh"));
        mHour.setFormat24Hour(Html.fromHtml("kk"));
        mMinutes.setFormat24Hour(Html.fromHtml("mm"));
        mMinutes.setFormat12Hour(Html.fromHtml("mm"));

    }

    @Override
    public void onDestroyView() {
        mView = null;
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    @Override
    public String getName() {
        return "squary_clock";
    }

    @Override
    public String getTitle() {
        return mResources.getString(R.string.clock_title_squary_clock);
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.squary_clock_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {
        createViews();
        setTextColor(1);
        return mRenderer.createPreview(getView(), width, height);
    }

    @Override
    public View getView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public View getBigClockView() {
        return null;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return totalHeight / 4;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {
        if (mView == null) return;
        switch (mResources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) {
            case Configuration.UI_MODE_NIGHT_YES:
                mHourBkg.setImageResource(R.drawable.squary_clock_dark);
                mMinBkg.setImageResource(R.drawable.squary_clock_dark);
                mHour.setTextColor(Color.WHITE);
                mMinutes.setTextColor(Color.WHITE);
                break;
            case Configuration.UI_MODE_NIGHT_NO:
                mHourBkg.setImageResource(R.drawable.squary_clock_light);
                mMinBkg.setImageResource(R.drawable.squary_clock_light);
                mHour.setTextColor(Color.BLACK);
                mMinutes.setTextColor(Color.BLACK);
                break;
        }
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {}

    @Override
    public void onTimeTick() {
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mView.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {}

    @Override
    public boolean shouldShowStatusArea() {
        return true;
    }
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            setTextColor(1);
        }
    };
}
