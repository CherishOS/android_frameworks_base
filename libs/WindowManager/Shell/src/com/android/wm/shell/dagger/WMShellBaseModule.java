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

package com.android.wm.shell.dagger;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootDisplayAreaOrganizer;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellCommandHandler;
import com.android.wm.shell.ShellCommandHandlerImpl;
import com.android.wm.shell.ShellInit;
import com.android.wm.shell.ShellInitImpl;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewFactory;
import com.android.wm.shell.TaskViewFactoryController;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.common.annotations.ShellSplashscreenThread;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.displayareahelper.DisplayAreaHelperController;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.fullscreen.FullscreenUnfoldController;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sizecompatui.SizeCompatUIController;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.splitscreen.StageTaskUnfoldController;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.StartingWindowTypeAlgorithm;
import com.android.wm.shell.tasksurfacehelper.TaskSurfaceHelper;
import com.android.wm.shell.tasksurfacehelper.TaskSurfaceHelperController;
import com.android.wm.shell.transition.ShellTransitions;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.UnfoldBackgroundController;

import java.util.Optional;

import javax.inject.Provider;

import dagger.BindsOptionalOf;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Provides basic dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines *common* dependencies across various SystemUI implementations,
 * dependencies that are device/form factor SystemUI implementation specific should go into their
 * respective modules (ie. {@link WMShellModule} for handheld, {@link TvWMShellModule} for tv, etc.)
 */
@Module(includes = WMShellConcurrencyModule.class)
public abstract class WMShellBaseModule {

    //
    // Internal common - Components used internally by multiple shell features
    //

    @WMSingleton
    @Provides
    static DisplayController provideDisplayController(Context context,
            IWindowManager wmService, @ShellMainThread ShellExecutor mainExecutor) {
        return new DisplayController(context, wmService, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DisplayInsetsController provideDisplayInsetsController( IWindowManager wmService,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new DisplayInsetsController(wmService, displayController, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DisplayLayout provideDisplayLayout() {
        return new DisplayLayout();
    }

    @WMSingleton
    @Provides
    static DragAndDropController provideDragAndDropController(Context context,
            DisplayController displayController, UiEventLogger uiEventLogger) {
        return new DragAndDropController(context, displayController, uiEventLogger);
    }

    @WMSingleton
    @Provides
    static ShellTaskOrganizer provideShellTaskOrganizer(@ShellMainThread ShellExecutor mainExecutor,
            Context context,
            SizeCompatUIController sizeCompatUI,
            Optional<RecentTasksController> recentTasksOptional
    ) {
        return new ShellTaskOrganizer(mainExecutor, context, sizeCompatUI, recentTasksOptional);
    }

    @WMSingleton
    @Provides
    static SizeCompatUIController provideSizeCompatUIController(Context context,
            DisplayController displayController, DisplayInsetsController displayInsetsController,
            DisplayImeController imeController, SyncTransactionQueue syncQueue) {
        return new SizeCompatUIController(context, displayController, displayInsetsController,
                imeController, syncQueue);
    }

    @WMSingleton
    @Provides
    static SyncTransactionQueue provideSyncTransactionQueue(TransactionPool pool,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new SyncTransactionQueue(pool, mainExecutor);
    }

    @WMSingleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    @WMSingleton
    @Provides
    static IconProvider provideIconProvider(Context context) {
        return new IconProvider(context);
    }

    // We currently dedupe multiple messages, so we use the shell main handler directly
    @WMSingleton
    @Provides
    static TaskStackListenerImpl providerTaskStackListenerImpl(
            @ShellMainThread Handler mainHandler) {
        return new TaskStackListenerImpl(mainHandler);
    }

    @WMSingleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @WMSingleton
    @Provides
    static WindowManagerShellWrapper provideWindowManagerShellWrapper(
            @ShellMainThread ShellExecutor mainExecutor) {
        return new WindowManagerShellWrapper(mainExecutor);
    }

    //
    // Bubbles
    //

    @WMSingleton
    @Provides
    static Optional<Bubbles> provideBubbles(Optional<BubbleController> bubbleController) {
        return bubbleController.map((controller) -> controller.asBubbles());
    }

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static Optional<BubbleController> provideBubbleController(Context context,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            ShellTaskOrganizer organizer,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            SyncTransactionQueue syncQueue) {
        return Optional.of(BubbleController.create(context, null /* synchronizer */,
                floatingContentCoordinator, statusBarService, windowManager,
                windowManagerShellWrapper, launcherApps, taskStackListener,
                uiEventLogger, organizer, displayController, mainExecutor, mainHandler, syncQueue));
    }

    //
    // Fullscreen
    //

    @WMSingleton
    @Provides
    static FullscreenTaskListener provideFullscreenTaskListener(
            SyncTransactionQueue syncQueue,
            Optional<FullscreenUnfoldController> optionalFullscreenUnfoldController,
            Optional<RecentTasksController> recentTasksOptional
    ) {
        return new FullscreenTaskListener(syncQueue, optionalFullscreenUnfoldController,
                recentTasksOptional);
    }

    //
    // Unfold transition
    //

    @WMSingleton
    @Provides
    static Optional<FullscreenUnfoldController> provideFullscreenUnfoldController(
            Context context,
            Optional<ShellUnfoldProgressProvider> progressProvider,
            Lazy<UnfoldBackgroundController> unfoldBackgroundController,
            DisplayInsetsController displayInsetsController,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return progressProvider.map(shellUnfoldTransitionProgressProvider ->
                new FullscreenUnfoldController(context, mainExecutor,
                        unfoldBackgroundController.get(), shellUnfoldTransitionProgressProvider,
                        displayInsetsController));
    }

    @Provides
    static Optional<StageTaskUnfoldController> provideStageTaskUnfoldController(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            Context context,
            TransactionPool transactionPool,
            Lazy<UnfoldBackgroundController> unfoldBackgroundController,
            DisplayInsetsController displayInsetsController,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return progressProvider.map(shellUnfoldTransitionProgressProvider ->
                new StageTaskUnfoldController(
                        context,
                        transactionPool,
                        shellUnfoldTransitionProgressProvider,
                        displayInsetsController,
                        unfoldBackgroundController.get(),
                        mainExecutor
                ));
    }

    @WMSingleton
    @Provides
    static UnfoldBackgroundController provideUnfoldBackgroundController(
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Context context
    ) {
        return new UnfoldBackgroundController(
                context,
                rootTaskDisplayAreaOrganizer
        );
    }

    //
    // Freeform (optional feature)
    //

    @BindsOptionalOf
    abstract Optional<FreeformTaskListener> optionalFreeformTaskListener();

    //
    // Hide display cutout
    //

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutout> provideHideDisplayCutout(
            Optional<HideDisplayCutoutController> hideDisplayCutoutController) {
        return hideDisplayCutoutController.map((controller) -> controller.asHideDisplayCutout());
    }

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutoutController> provideHideDisplayCutoutController(Context context,
            DisplayController displayController, @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(
                HideDisplayCutoutController.create(context, displayController, mainExecutor));
    }

    //
    // One handed mode (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<OneHanded> provideOneHanded(Optional<OneHandedController> oneHandedController) {
        return oneHandedController.map((controller) -> controller.asOneHanded());
    }

    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    static Optional<OneHandedController> provideOneHandedController(Context context,
            WindowManager windowManager, DisplayController displayController,
            DisplayLayout displayLayout, TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return Optional.ofNullable(OneHandedController.create(context, windowManager,
                displayController, displayLayout, taskStackListener, uiEventLogger, mainExecutor,
                mainHandler));
    }

    //
    // Task to Surface communication
    //

    @WMSingleton
    @Provides
    static Optional<TaskSurfaceHelper> provideTaskSurfaceHelper(
            Optional<TaskSurfaceHelperController> taskSurfaceController) {
        return taskSurfaceController.map((controller) -> controller.asTaskSurfaceHelper());
    }

    @Provides
    static Optional<TaskSurfaceHelperController> provideTaskSurfaceHelperController(
            ShellTaskOrganizer taskOrganizer, @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(new TaskSurfaceHelperController(taskOrganizer, mainExecutor));
    }

    @WMSingleton
    @Provides
    static Optional<DisplayAreaHelper> provideDisplayAreaHelper(
            @ShellMainThread ShellExecutor mainExecutor,
            RootDisplayAreaOrganizer rootDisplayAreaOrganizer) {
        return Optional.ofNullable(new DisplayAreaHelperController(mainExecutor,
                rootDisplayAreaOrganizer));
    }

    //
    // Pip (optional feature)
    //

    @WMSingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            PipTouchHandler pipTouchHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipAppOpsListener(context, pipTouchHandler.getMotionHelper(), mainExecutor);
    }

    // Needs handler for registering broadcast receivers
    @WMSingleton
    @Provides
    static PipMediaController providePipMediaController(Context context,
            @ShellMainThread Handler mainHandler) {
        return new PipMediaController(context, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipSurfaceTransactionHelper providePipSurfaceTransactionHelper() {
        return new PipSurfaceTransactionHelper();
    }

    @WMSingleton
    @Provides
    static PipUiEventLogger providePipUiEventLogger(UiEventLogger uiEventLogger,
            PackageManager packageManager) {
        return new PipUiEventLogger(uiEventLogger, packageManager);
    }

    @BindsOptionalOf
    abstract PipTouchHandler optionalPipTouchHandler();

    //
    // Recent tasks
    //

    @WMSingleton
    @Provides
    static Optional<RecentTasks> provideRecentTasks(
            Optional<RecentTasksController> recentTasksController) {
        return recentTasksController.map((controller) -> controller.asRecentTasks());
    }

    @WMSingleton
    @Provides
    static Optional<RecentTasksController> provideRecentTasksController(
            Context context,
            TaskStackListenerImpl taskStackListener,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return Optional.ofNullable(
                RecentTasksController.create(context, taskStackListener, mainExecutor));
    }

    //
    // Shell transitions
    //

    @WMSingleton
    @Provides
    static ShellTransitions provideRemoteTransitions(Transitions transitions) {
        return transitions.asRemoteTransitions();
    }

    @WMSingleton
    @Provides
    static Transitions provideTransitions(ShellTaskOrganizer organizer, TransactionPool pool,
            DisplayController displayController, Context context,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellAnimationThread ShellExecutor animExecutor) {
        return new Transitions(organizer, pool, displayController, context, mainExecutor,
                animExecutor);
    }

    //
    // Split/multiwindow
    //

    @WMSingleton
    @Provides
    static RootTaskDisplayAreaOrganizer provideRootTaskDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor, Context context) {
        return new RootTaskDisplayAreaOrganizer(mainExecutor, context);
    }

    @WMSingleton
    @Provides
    static RootDisplayAreaOrganizer provideRootDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor) {
        return new RootDisplayAreaOrganizer(mainExecutor);
    }

    @WMSingleton
    @Provides
    static Optional<SplitScreen> provideSplitScreen(
            Optional<SplitScreenController> splitScreenController) {
        return splitScreenController.map((controller) -> controller.asSplitScreen());
    }

    @WMSingleton
    @Provides
    static Optional<SplitScreenController> provideSplitScreenController(
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, Transitions transitions,
            TransactionPool transactionPool, IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> stageTaskUnfoldControllerProvider) {
        if (ActivityTaskManager.supportsSplitScreenMultiWindow(context)) {
            return Optional.of(new SplitScreenController(shellTaskOrganizer, syncQueue, context,
                    rootTaskDisplayAreaOrganizer, mainExecutor, displayImeController,
                    displayInsetsController, transitions, transactionPool, iconProvider,
                    recentTasks, stageTaskUnfoldControllerProvider));
        } else {
            return Optional.empty();
        }
    }

    // Legacy split (optional feature)

    @WMSingleton
    @Provides
    static Optional<LegacySplitScreen> provideLegacySplitScreen(
            Optional<LegacySplitScreenController> splitScreenController) {
        return splitScreenController.map((controller) -> controller.asLegacySplitScreen());
    }

    @BindsOptionalOf
    abstract LegacySplitScreenController optionalLegacySplitScreenController();

    // App Pairs (optional feature)

    @WMSingleton
    @Provides
    static Optional<AppPairs> provideAppPairs(Optional<AppPairsController> appPairsController) {
        return appPairsController.map((controller) -> controller.asAppPairs());
    }

    @BindsOptionalOf
    abstract AppPairsController optionalAppPairs();

    // Starting window

    @WMSingleton
    @Provides
    static Optional<StartingSurface> provideStartingSurface(
            StartingWindowController startingWindowController) {
        return Optional.of(startingWindowController.asStartingSurface());
    }

    @WMSingleton
    @Provides
    static StartingWindowController provideStartingWindowController(Context context,
            @ShellSplashscreenThread ShellExecutor splashScreenExecutor,
            StartingWindowTypeAlgorithm startingWindowTypeAlgorithm, IconProvider iconProvider,
            TransactionPool pool) {
        return new StartingWindowController(context, splashScreenExecutor,
                startingWindowTypeAlgorithm, iconProvider, pool);
    }

    //
    // Task view factory
    //

    @WMSingleton
    @Provides
    static Optional<TaskViewFactory> provideTaskViewFactory(
            TaskViewFactoryController taskViewFactoryController) {
        return Optional.of(taskViewFactoryController.asTaskViewFactory());
    }

    @WMSingleton
    @Provides
    static TaskViewFactoryController provideTaskViewFactoryController(
            ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor,
            SyncTransactionQueue syncQueue) {
        return new TaskViewFactoryController(shellTaskOrganizer, mainExecutor, syncQueue);
    }

    //
    // Misc
    //

    @WMSingleton
    @Provides
    static ShellInit provideShellInit(ShellInitImpl impl) {
        return impl.asShellInit();
    }

    @WMSingleton
    @Provides
    static ShellInitImpl provideShellInitImpl(DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<BubbleController> bubblesOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<AppPairsController> appPairsOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Optional<FullscreenUnfoldController> appUnfoldTransitionController,
            Optional<Optional<FreeformTaskListener>> freeformTaskListener,
            Optional<RecentTasksController> recentTasksOptional,
            Transitions transitions,
            StartingWindowController startingWindow,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new ShellInitImpl(displayController,
                displayImeController,
                displayInsetsController,
                dragAndDropController,
                shellTaskOrganizer,
                bubblesOptional,
                splitScreenOptional,
                appPairsOptional,
                pipTouchHandlerOptional,
                fullscreenTaskListener,
                appUnfoldTransitionController,
                freeformTaskListener,
                recentTasksOptional,
                transitions,
                startingWindow,
                mainExecutor);
    }

    /**
     * Note, this is only optional because we currently pass this to the SysUI component scope and
     * for non-primary users, we may inject a null-optional for that dependency.
     */
    @WMSingleton
    @Provides
    static Optional<ShellCommandHandler> provideShellCommandHandler(ShellCommandHandlerImpl impl) {
        return Optional.of(impl.asShellCommandHandler());
    }

    @WMSingleton
    @Provides
    static ShellCommandHandlerImpl provideShellCommandHandlerImpl(
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreenController> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHandedController> oneHandedOptional,
            Optional<HideDisplayCutoutController> hideDisplayCutout,
            Optional<AppPairsController> appPairsOptional,
            Optional<RecentTasksController> recentTasksOptional,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new ShellCommandHandlerImpl(shellTaskOrganizer,
                legacySplitScreenOptional, splitScreenOptional, pipOptional, oneHandedOptional,
                hideDisplayCutout, appPairsOptional, recentTasksOptional, mainExecutor);
    }
}
