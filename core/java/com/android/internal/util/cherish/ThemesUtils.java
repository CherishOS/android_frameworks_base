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
            "com.android.systemui.brightness.slider.danielgradient",
            "com.android.systemui.brightness.slider.mememinigradient",
            "com.android.systemui.brightness.slider.memeroundgradient",
            "com.android.systemui.brightness.slider.memeroundstrokegradient",
            "com.android.systemui.brightness.slider.memestrokegradient",
            "com.android.systemui.brightness.slider.minihalf",
            "com.android.systemui.brightness.slider.half",
	      "com.android.systemui.brightness.slider.oos",
    };

    //Navbar colors
    public static final String NAVBAR_COLOR_PURP = "com.gnonymous.gvisualmod.pgm_purp";
    public static final String NAVBAR_COLOR_ORCD = "com.gnonymous.gvisualmod.pgm_orcd";
    public static final String NAVBAR_COLOR_OPRD = "com.gnonymous.gvisualmod.pgm_oprd";

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

// QS Tile Styles
public static final String[] QS_TILE_THEMES = {
    "com.android.systemui.qstile.default", // 0
    "com.android.systemui.qstile.circletrim", // 1
    "com.android.systemui.qstile.dualtonecircletrim", // 2
    "com.android.systemui.qstile.squircletrim", // 3
    "com.android.systemui.qstile.wavey", // 4
    "com.android.systemui.qstile.pokesign", // 5
    "com.android.systemui.qstile.ninja", // 6
    "com.android.systemui.qstile.dottedcircle", // 7
    "com.android.systemui.qstile.attemptmountain", // 8
    "com.android.systemui.qstile.squaremedo", // 9
    "com.android.systemui.qstile.inkdrop", // 10
    "com.android.systemui.qstile.cookie", // 11
    "com.android.systemui.qstile.circleoutline", // 12
    "com.bootleggers.qstile.cosmos", // 13
    "com.bootleggers.qstile.divided", // 14
    "com.bootleggers.qstile.neonlike", // 15
    "com.bootleggers.qstile.oos", // 16
    "com.bootleggers.qstile.triangles", // 17
};

// Switches qs tile style to user selected.
public static void updateNewTileStyle(IOverlayManager om, int userId, int qsTileStyle) {
    if (qsTileStyle == 0) {
        stockNewTileStyle(om, userId);
    } else {
        try {
            om.setEnabled(QS_TILE_THEMES[qsTileStyle],
                    true, userId);
        } catch (RemoteException e) {
            Log.w(TAG, "Can't change qs tile style", e);
        }
    }
}

// Switches qs tile style back to stock.
public static void stockNewTileStyle(IOverlayManager om, int userId) {
    // skip index 0
    for (int i = 1; i < QS_TILE_THEMES.length; i++) {
        String qstiletheme = QS_TILE_THEMES[i];
        try {
            om.setEnabled(qstiletheme,
                    false /*disable*/, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}

	// QS header themes
    private static final String[] QS_HEADER_THEMES = {
        "com.android.systemui.qsheader.black", // 0
        "com.android.systemui.qsheader.grey", // 1
        "com.android.systemui.qsheader.lightgrey", // 2
        "com.android.systemui.qsheader.accent", // 3
        "com.android.systemui.qsheader.transparent", // 4
    };

    // Switches qs header style to user selected.
    public static void updateQSHeaderStyle(IOverlayManager om, int userId, int qsHeaderStyle) {
        if (qsHeaderStyle == 0) {
            stockQSHeaderStyle(om, userId);
        } else {
            try {
                om.setEnabled(QS_HEADER_THEMES[qsHeaderStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change qs header theme", e);
            }
        }
    }

    // Switches qs header style back to stock.
    public static void stockQSHeaderStyle(IOverlayManager om, int userId) {
        // skip index 0
        for (int i = 1; i < QS_HEADER_THEMES.length; i++) {
            String qsheadertheme = QS_HEADER_THEMES[i];
            try {
                om.setEnabled(qsheadertheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

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
