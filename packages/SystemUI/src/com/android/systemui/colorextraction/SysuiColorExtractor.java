/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.colorextraction;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;

/**
 * ColorExtractor aware of wallpaper visibility
 */
public class SysuiColorExtractor extends ColorExtractor {
    private static final String TAG = "SysuiColorExtractor";
    private boolean mWallpaperVisible;
    // Colors to return when the wallpaper isn't visible
    private final GradientColors mWpHiddenColors;

    public SysuiColorExtractor(Context context) {
        this(context, new Tonal(), true);
    }

    @VisibleForTesting
    public SysuiColorExtractor(Context context, ExtractionType type, boolean registerVisibility) {
        super(context, type);

        mWpHiddenColors = new GradientColors();
        mWpHiddenColors.setMainColor(FALLBACK_COLOR);
        mWpHiddenColors.setSecondaryColor(FALLBACK_COLOR);

        if (registerVisibility) {
            try {
                IWindowManager windowManagerService = WindowManagerGlobal.getWindowManagerService();
                Handler handler = Handler.getMain();
                boolean visible = windowManagerService.registerWallpaperVisibilityListener(
                        new IWallpaperVisibilityListener.Stub() {
                            @Override
                            public void onWallpaperVisibilityChanged(boolean newVisibility,
                                    int displayId) throws RemoteException {
                                handler.post(() -> setWallpaperVisible(newVisibility));
                            }
                        }, Display.DEFAULT_DISPLAY);
                setWallpaperVisible(visible);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't listen to wallpaper visibility changes", e);
            }
        }
    }

    /**
     * Get TYPE_NORMAL colors when wallpaper is visible, or fallback otherwise.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @return colors
     */
    @Override
    public GradientColors getColors(int which) {
        return getColors(which, TYPE_NORMAL);
    }

    /**
     * Wallpaper colors when the wallpaper is visible, fallback otherwise.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @return colors
     */
    @Override
    public GradientColors getColors(int which, int type) {
        return getColors(which, type, false /* ignoreVisibility */);
    }

    /**
     * Get TYPE_NORMAL colors, possibly ignoring wallpaper visibility.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param ignoreWallpaperVisibility whether you want fallback colors or not if the wallpaper
     *                                  isn't visible
     * @return
     */
    public GradientColors getColors(int which, boolean ignoreWallpaperVisibility) {
        return getColors(which, TYPE_NORMAL, ignoreWallpaperVisibility);
    }

    /**
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @param ignoreWallpaperVisibility true if true wallpaper colors should be returning
     *                                  if it's visible or not
     * @return colors
     */
    public GradientColors getColors(int which, int type, boolean ignoreWallpaperVisibility) {
        if (mWallpaperVisible || ignoreWallpaperVisibility) {
            return super.getColors(which, type);
        } else {
            return mWpHiddenColors;
        }
    }

    @VisibleForTesting
    void setWallpaperVisible(boolean visible) {
        if (mWallpaperVisible != visible) {
            mWallpaperVisible = visible;
            triggerColorsChanged(WallpaperManager.FLAG_SYSTEM);
        }
    }

}
