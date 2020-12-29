/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.cherish;

import static android.os.UserHandle.USER_SYSTEM;

import android.app.UiModeManager;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.os.RemoteException;
import android.util.Log;
public class ThemesUtils {
	
	public static final String TAG = "ThemesUtils";
	
	public static final String[] BRIGHTNESS_SLIDER_THEMES = {
            "com.android.systemui.brightness.slider.default",
            "com.android.systemui.brightness.slider.daniel",
            "com.android.systemui.brightness.slider.mememini",
            "com.android.systemui.brightness.slider.memeround",
            "com.android.systemui.brightness.slider.memeroundstroke",
            "com.android.systemui.brightness.slider.memestroke",
    };

    public static final String[] SOLARIZED_DARK = {
            "com.android.theme.solarizeddark.system",
            "com.android.theme.solarizeddark.systemui",
    };

    public static final String[] BAKED_GREEN = {
            "com.android.theme.bakedgreen.system",
            "com.android.theme.bakedgreen.systemui",
    };

    public static final String[] CHOCO_X = {
            "com.android.theme.chocox.system",
            "com.android.theme.chocox.systemui",
    };

    public static final String[] PITCH_BLACK = {
            "com.android.theme.pitchblack.system",
            "com.android.theme.pitchblack.systemui",
    };

    public static final String[] DARK_GREY = {
            "com.android.theme.darkgrey.system",
            "com.android.theme.darkgrey.systemui",
    };
    public static final String[] MATERIAL_OCEAN = {
            "com.android.theme.materialocean.system",
            "com.android.theme.materialocean.systemui",
    };

    public static final String[] CLEAR_SPRING = {
            "com.android.theme.clearspring.system",
            "com.android.theme.clearspring.systemui",
    };

    public static final String[] UI_THEMES = {
            "com.android.systemui.ui.default",
            "com.android.systemui.ui.nocornerradius",
            "com.android.systemui.ui.rectangle",
            "com.android.systemui.ui.roundlarge",
            "com.android.systemui.ui.roundmedium",
    };

public static final String[] PANEL_BG_STYLE = {
        "com.jrinfected.panel.batik", // 1
        "com.jrinfected.panel.kece", // 2
        "com.jrinfected.panel.outline", // 3
};

public static final String[] QS_SHAPE = {
        "com.jrinfected.qs.shape.a",
        "com.jrinfected.qs.shape.b",
        "com.jrinfected.qs.shape.c",
        "com.jrinfected.qs.shape.d",
        "com.jrinfected.qs.shape.e",
        "com.jrinfected.qs.shape.f",
        "com.jrinfected.qs.shape.g",
        "com.jrinfected.qs.shape.h",
        "com.jrinfected.qs.shape.i",
        "com.jrinfected.qs.shape.j",
        "com.jrinfected.qs.shape.k",
        "com.jrinfected.qs.shape.l",
        "com.jrinfected.qs.shape.m",
        "com.jrinfected.qs.shape.n",
        "com.jrinfected.qs.shape.o",
        "com.jrinfected.qs.shape.p",
        "com.jrinfected.qs.shape.q",
        "com.jrinfected.qs.shape.r",
        "com.jrinfected.qs.shape.s",
};

	public static void updateBrightnessSliderStyle(IOverlayManager om, int userId, int brightnessSliderStyle) {
        if (brightnessSliderStyle == 0) {
            stockBrightnessSliderStyle(om, userId);
        } else {
            try {
                om.setEnabled(UI_THEMES[brightnessSliderStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change brightness slider theme", e);
            }
        }
    }

    public static void stockBrightnessSliderStyle(IOverlayManager om, int userId) {
        for (int i = 0; i < UI_THEMES.length; i++) {
            String brightnessSlidertheme = BRIGHTNESS_SLIDER_THEMES[i];
            try {
                om.setEnabled(brightnessSlidertheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
	
	public static void updateUIStyle(IOverlayManager om, int userId, int uiStyle) {
        if (uiStyle == 0) {
            stockUIStyle(om, userId);
        } else {
            try {
                om.setEnabled(UI_THEMES[uiStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change switch theme", e);
            }
        }
    }

    public static void stockUIStyle(IOverlayManager om, int userId) {
        for (int i = 0; i < UI_THEMES.length; i++) {
            String uitheme = UI_THEMES[i];
            try {
                om.setEnabled(uitheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}

