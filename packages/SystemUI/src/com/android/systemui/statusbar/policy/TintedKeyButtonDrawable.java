/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.ColorInt;
import android.graphics.drawable.Drawable;

import com.android.internal.graphics.ColorUtils;

/**
 * Drawable for {@link KeyButtonView}s which contains a single asset and colors for light and dark
 * navigation bar mode.
 */
public class TintedKeyButtonDrawable extends KeyButtonDrawable {

    private final int mLightColor;
    private final int mDarkColor;

    public static TintedKeyButtonDrawable create(Drawable drawable, @ColorInt int lightColor,
            @ColorInt int darkColor) {
        return new TintedKeyButtonDrawable(new Drawable[] { drawable }, lightColor, darkColor);
    }

    private TintedKeyButtonDrawable(Drawable[] drawables, int lightColor, int darkColor){
        super(drawables);
        mLightColor = lightColor;
        mDarkColor = darkColor;
    }

    @Override
    public void setDarkIntensity(float intensity) {
        // Duplicate intensity scaling from KeyButtonDrawable
        int intermediateColor = ColorUtils.compositeColors(
                setAlphaFloat(mDarkColor, intensity),
                setAlphaFloat(mLightColor,1f - intensity));
        getDrawable(0).setTint(intermediateColor);
        invalidateSelf();
    }

    private int setAlphaFloat(int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, (int) (alpha * 255f));
    }
}
