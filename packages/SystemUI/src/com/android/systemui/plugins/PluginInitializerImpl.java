/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.plugins;

import android.content.Context;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.shared.plugins.PluginInitializer;
import com.android.systemui.shared.plugins.PluginManagerImpl;

import javax.inject.Inject;
import javax.inject.Singleton;

/** */
@Singleton
public class PluginInitializerImpl implements PluginInitializer {

    /**
     * True if WTFs should lead to crashes
     */
    private static final boolean WTFS_SHOULD_CRASH = false;
    private boolean mWtfsSet;

    @Inject
    public PluginInitializerImpl(PluginDependencyProvider  dependencyProvider) {
        dependencyProvider.allowPluginDependency(ActivityStarter.class);
    }

    @Override
    public String[] getPrivilegedPlugins(Context context) {
        return context.getResources().getStringArray(R.array.config_pluginWhitelist);
    }


    @Override
    public void handleWtfs() {
        if (WTFS_SHOULD_CRASH && !mWtfsSet) {
            mWtfsSet = true;
            Log.setWtfHandler((tag, what, system) -> {
                throw new PluginManagerImpl.CrashWhilePluginActiveException(what);
            });
        }
    }
}
