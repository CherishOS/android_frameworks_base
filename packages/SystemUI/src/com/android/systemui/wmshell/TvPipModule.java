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

package com.android.systemui.wmshell;

import android.content.Context;
import android.os.Handler;
import android.view.LayoutInflater;

import com.android.systemui.dagger.SysUISingleton;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.tv.PipController;
import com.android.wm.shell.pip.tv.PipControlsView;
import com.android.wm.shell.pip.tv.PipControlsViewController;
import com.android.wm.shell.pip.tv.PipNotification;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for TV Pip.
 */
@Module
public abstract class TvPipModule {

    @SysUISingleton
    @Provides
    static Pip providePipController(Context context,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        return new PipController(context, pipBoundsHandler, pipTaskOrganizer,
                windowManagerShellWrapper);
    }

    @SysUISingleton
    @Provides
    static PipControlsViewController providePipControlsViewContrller(
            PipControlsView pipControlsView, PipController pipController,
            LayoutInflater layoutInflater, Handler handler) {
        return new PipControlsViewController(pipControlsView, pipController, layoutInflater,
                handler);
    }

    @SysUISingleton
    @Provides
    static PipControlsView providePipControlsView(Context context) {
        return new PipControlsView(context, null);
    }

    @SysUISingleton
    @Provides
    static PipNotification providePipNotification(Context context,
            PipController pipController) {
        return new PipNotification(context, pipController);
    }

    @SysUISingleton
    @Provides
    static PipBoundsHandler providePipBoundsHandler(Context context) {
        return new PipBoundsHandler(context);
    }

    @SysUISingleton
    @Provides
    static PipBoundsState providePipBoundsState() {
        return new PipBoundsState();
    }

    @SysUISingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsHandler pipBoundsHandler,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsHandler,
                pipSurfaceTransactionHelper, splitScreenOptional, displayController,
                pipUiEventLogger, shellTaskOrganizer);
    }
}
