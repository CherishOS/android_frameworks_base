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

package com.android.systemui.dagger;

import android.content.Context;

import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SystemUIAppComponentFactory;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.onehanded.dagger.OneHandedModule;
import com.android.systemui.pip.phone.dagger.PipModule;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.InjectionInflationController;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;

/**
 * Root component for Dagger injection.
 */
// TODO(b/162923491): Move most of these modules to SysUIComponent.
@Singleton
@Component(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        DependencyBinder.class,
        OneHandedModule.class,
        PipModule.class,
        SystemServicesModule.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        SystemUIDefaultModule.class,
        WMModule.class})
public interface GlobalRootComponent {

    /**
     * Builder for a GlobalRootComponent.
     */
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder context(Context context);

        GlobalRootComponent build();
    }

    /**
     * Builder for a WMComponent.
     */
    WMComponent.Builder getWMComponentBuilder();

    /**
     * Builder for a SysuiComponent.
     */
    SysUIComponent.Builder getSysUIComponent();

    /**
     * Provides a BootCompleteCache.
     */
    @Singleton
    BootCompleteCacheImpl provideBootCacheImpl();

    /**
     * Creates a ContextComponentHelper.
     */
    @Singleton
    ConfigurationController getConfigurationController();

    /**
     * Creates a ContextComponentHelper.
     */
    @Singleton
    ContextComponentHelper getContextComponentHelper();

    /**
     * Main dependency providing module.
     */
    @Singleton
    Dependency createDependency();

    /** */
    @Singleton
    DumpManager createDumpManager();

    /**
     * Creates a InitController.
     */
    @Singleton
    InitController getInitController();

    /**
     * ViewInstanceCreator generates all Views that need injection.
     */
    InjectionInflationController.ViewInstanceCreator.Factory createViewInstanceCreatorFactory();

    /**
     * Member injection into the supplied argument.
     */
    void inject(SystemUIAppComponentFactory factory);

    /**
     * Member injection into the supplied argument.
     */
    void inject(KeyguardSliceProvider keyguardSliceProvider);
}
