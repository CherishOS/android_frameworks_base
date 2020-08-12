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

package com.android.systemui;

import com.android.systemui.dagger.DependencyBinder;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SystemServicesModule;
import com.android.systemui.dagger.SystemUIModule;
import com.android.systemui.onehanded.dagger.OneHandedModule;
import com.android.systemui.pip.phone.dagger.PipModule;

import javax.inject.Singleton;

import dagger.Component;

/** Car subclass for GlobalRootComponent. */
@Singleton
@Component(
        modules = {
                CarComponentBinder.class,
                DependencyProvider.class,
                DependencyBinder.class,
                PipModule.class,
                OneHandedModule.class,
                SystemServicesModule.class,
                SystemUIModule.class,
                CarSystemUIModule.class,
                CarSystemUIBinder.class
        })
public interface CarGlobalRootComponent extends GlobalRootComponent {
    /**
     * Builder for a CarGlobalRootComponent.
     */
    @Component.Builder
    interface Builder extends GlobalRootComponent.Builder {
        CarGlobalRootComponent build();
    }
}
