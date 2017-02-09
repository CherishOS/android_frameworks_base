/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;

import com.android.systemui.ConfigurationChangedReceiver;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.util.ArrayList;

public class ConfigurationControllerImpl implements ConfigurationController,
        ConfigurationChangedReceiver {

    private final ArrayList<ConfigurationListener> mListeners = new ArrayList<>();
    private int mDensity;
    private float mFontScale;

    public ConfigurationControllerImpl(Context context) {
        Configuration currentConfig = context.getResources().getConfiguration();
        mFontScale = currentConfig.fontScale;
        mDensity = currentConfig.densityDpi;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mListeners.forEach(l -> l.onConfigChanged(newConfig));
        final float fontScale = newConfig.fontScale;
        final int density = newConfig.densityDpi;
        if (density != mDensity || mFontScale != fontScale) {
            mListeners.forEach(l -> l.onDensityOrFontScaleChanged());
            mDensity = density;
            mFontScale = fontScale;
        }
    }

    @Override
    public void addCallback(ConfigurationListener listener) {
        mListeners.add(listener);
        listener.onDensityOrFontScaleChanged();
    }

    @Override
    public void removeCallback(ConfigurationListener listener) {
        mListeners.remove(listener);
    }
}
