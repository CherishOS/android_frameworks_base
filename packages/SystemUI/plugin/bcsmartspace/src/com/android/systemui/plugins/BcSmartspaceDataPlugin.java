/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.plugins;

import android.app.smartspace.SmartspaceTarget;
import android.os.Parcelable;
import android.view.ViewGroup;

import com.android.systemui.plugins.annotations.ProvidesInterface;

import java.util.List;

/**
 * Interface to provide SmartspaceTargets to BcSmartspace.
 */
@ProvidesInterface(action = BcSmartspaceDataPlugin.ACTION, version = BcSmartspaceDataPlugin.VERSION)
public interface BcSmartspaceDataPlugin extends Plugin {
    String ACTION = "com.android.systemui.action.PLUGIN_BC_SMARTSPACE_DATA";
    int VERSION = 1;

    /** Register a listener to get Smartspace data. */
    void registerListener(SmartspaceTargetListener listener);

    /** Unregister a listener. */
    void unregisterListener(SmartspaceTargetListener listener);

    /**
     * Create a view to be shown within the parent. Do not add the view, as the parent
     * will be responsible for correctly setting the LayoutParams
     */
    default SmartspaceView getView(ViewGroup parent) {
        return null;
    }

    /** Updates Smartspace data and propagates it to any listeners. */
    void onTargetsAvailable(List<SmartspaceTarget> targets);

    /** Provides Smartspace data to registered listeners. */
    interface SmartspaceTargetListener {
        /** Each Parcelable is a SmartspaceTarget that represents a card. */
        void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets);
    }

    /** View to which this plugin can be registered, in order to get updates. */
    interface SmartspaceView {
        void registerDataProvider(BcSmartspaceDataPlugin plugin);

        void setPrimaryTextColor(int color);
    }
}
