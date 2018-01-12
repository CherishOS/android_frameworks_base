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

package com.android.server.am;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Maps global system settings to system properties.
 * <p>The properties are dynamically updated when settings change.
 */
class GlobalSettingsToPropertiesMapper {

    private static final String TAG = "GlobalSettingsToPropertiesMapper";

    private static final String[][] sGlobalSettingsMapping = new String[][] {
    //  List mapping entries in the following format:
    //  {Settings.Global.SETTING_NAME, "system_property_name"},
        {Settings.Global.SYS_VDSO, "sys.vdso"},
    };


    private final ContentResolver mContentResolver;
    private final String[][] mGlobalSettingsMapping;

    @VisibleForTesting
    GlobalSettingsToPropertiesMapper(ContentResolver contentResolver,
            String[][] globalSettingsMapping) {
        mContentResolver = contentResolver;
        mGlobalSettingsMapping = globalSettingsMapping;
    }

    void updatePropertiesFromGlobalSettings() {
        for (String[] entry : mGlobalSettingsMapping) {
            final String settingName = entry[0];
            final String propName = entry[1];
            Uri settingUri = Settings.Global.getUriFor(settingName);
            Preconditions.checkNotNull(settingUri, "Setting " + settingName + " not found");
            ContentObserver co = new ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) {
                    updatePropertyFromSetting(settingName, propName);
                }
            };
            updatePropertyFromSetting(settingName, propName);
            mContentResolver.registerContentObserver(settingUri, false, co);
        }
    }

    public static void start(ContentResolver contentResolver) {
        new GlobalSettingsToPropertiesMapper(contentResolver, sGlobalSettingsMapping)
                .updatePropertiesFromGlobalSettings();
    }

    private String getGlobalSetting(String name) {
        return Settings.Global.getString(mContentResolver, name);
    }

    private void setProperty(String key, String value) {
        // Check if need to clear the property
        if (value == null) {
            // It's impossible to remove system property, therefore we check previous value to
            // avoid setting an empty string if the property wasn't set.
            if (TextUtils.isEmpty(systemPropertiesGet(key))) {
                return;
            }
            value = "";
        }
        try {
            systemPropertiesSet(key, value);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to set property " + key + " value '" + value + "'", e);
        }
    }

    @VisibleForTesting
    protected String systemPropertiesGet(String key) {
        return SystemProperties.get(key);
    }

    @VisibleForTesting
    protected void systemPropertiesSet(String key, String value) {
        SystemProperties.set(key, value);
    }

    @VisibleForTesting
    void updatePropertyFromSetting(String settingName, String propName) {
        String settingValue = getGlobalSetting(settingName);
        setProperty(propName, settingValue);
    }
}
