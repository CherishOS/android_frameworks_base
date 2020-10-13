/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.view.IWindowManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipMediaController;
import com.android.wm.shell.pip.phone.PipMenuActivityController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell} which could be customized among different
 * branches of SystemUI.
 */
// TODO(b/162923491): Move most of these dependencies into WMSingleton scope.
@Module(includes = WMShellBaseModule.class)
public class WMShellModule {
    @SysUISingleton
    @Provides
    static DisplayImeController provideDisplayImeController(IWindowManager wmService,
            DisplayController displayController, @Main Executor mainExecutor,
            TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, mainExecutor,
                transactionPool);
    }

    @SysUISingleton
    @Provides
    static Pip providePipController(Context context,
            DisplayController displayController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsHandler pipBoundsHandler,
            PipBoundsState pipBoundsState,
            PipMediaController pipMediaController,
            PipMenuActivityController pipMenuActivityController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        return new PipController(context, displayController,
                pipAppOpsListener, pipBoundsHandler, pipBoundsState, pipMediaController,
                pipMenuActivityController, pipTaskOrganizer, pipTouchHandler,
                windowManagerShellWrapper);
    }

    @SysUISingleton
    @Provides
    static SplitScreen provideSplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, @Main Handler handler,
            TransactionPool transactionPool, ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue) {
        return new SplitScreenController(context, displayController, systemWindows,
                displayImeController, handler, transactionPool, shellTaskOrganizer, syncQueue);
    }

    @SysUISingleton
    @Provides
    static PipBoundsState providePipBoundsState() {
        return new PipBoundsState();
    }

    @SysUISingleton
    @Provides
    static PipBoundsHandler providesPipBoundsHandler(Context context) {
        return new PipBoundsHandler(context);
    }

    @SysUISingleton
    @Provides
    static PipMenuActivityController providesPipMenuActivityController(Context context,
            PipMediaController pipMediaController, PipTaskOrganizer pipTaskOrganizer) {
        return new PipMenuActivityController(context, pipMediaController, pipTaskOrganizer);
    }

    @SysUISingleton
    @Provides
    static PipTouchHandler providesPipTouchHandler(Context context,
            PipMenuActivityController menuActivityController, PipBoundsHandler pipBoundsHandler,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger) {
        return new PipTouchHandler(context, menuActivityController, pipBoundsHandler,
                pipBoundsState, pipTaskOrganizer, floatingContentCoordinator, pipUiEventLogger);
    }

    @SysUISingleton
    @Provides
    static PipTaskOrganizer providesPipTaskOrganizer(Context context,
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
