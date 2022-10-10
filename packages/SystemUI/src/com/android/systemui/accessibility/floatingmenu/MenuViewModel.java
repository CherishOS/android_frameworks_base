/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.List;

/**
 * The view model provides the menu information from the repository{@link MenuInfoRepository} for
 * the menu view{@link MenuView}.
 */
class MenuViewModel implements MenuInfoRepository.OnSettingsContentsChanged {
    private final MutableLiveData<List<AccessibilityTarget>> mTargetFeaturesData =
            new MutableLiveData<>();
    private final MenuInfoRepository mInfoRepository;

    MenuViewModel(Context context) {
        mInfoRepository = new MenuInfoRepository(context, /* settingsContentsChanged= */ this);
    }

    @Override
    public void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures) {
        mTargetFeaturesData.setValue(newTargetFeatures);
    }

    LiveData<List<AccessibilityTarget>> getTargetFeaturesData() {
        mInfoRepository.loadMenuTargetFeatures(mTargetFeaturesData::setValue);
        return mTargetFeaturesData;
    }

    void registerContentObservers() {
        mInfoRepository.registerContentObservers();
    }

    void unregisterContentObservers() {
        mInfoRepository.unregisterContentObservers();
    }
}
