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

import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.UserManager;
import android.view.Choreographer;
import android.view.WindowManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubblePositioner;
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
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformComponents;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransition;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PhonePipMenuController;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipKeepClearAlgorithm;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.UnfoldAnimationController;
import com.android.wm.shell.unfold.UnfoldBackgroundController;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;
import com.android.wm.shell.unfold.animation.SplitTaskUnfoldAnimator;
import com.android.wm.shell.unfold.animation.UnfoldTaskAnimator;
import com.android.wm.shell.unfold.qualifier.UnfoldShellTransition;
import com.android.wm.shell.unfold.qualifier.UnfoldTransition;
import com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines Shell dependencies for handheld SystemUI implementation.  Common
 * dependencies should go into {@link WMShellBaseModule}.
 */
@Module(includes = WMShellBaseModule.class)
public abstract class WMShellModule {

    //
    // Bubbles
    //

    @WMSingleton
    @Provides
    static BubbleLogger provideBubbleLogger(UiEventLogger uiEventLogger) {
        return new BubbleLogger(uiEventLogger);
    }

    @WMSingleton
    @Provides
    static BubblePositioner provideBubblePositioner(Context context,
            WindowManager windowManager) {
        return new BubblePositioner(context, windowManager);
    }

    @WMSingleton
    @Provides
    static BubbleData provideBubbleData(Context context,
            BubbleLogger logger,
            BubblePositioner positioner,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new BubbleData(context, logger, positioner, mainExecutor);
    }

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static BubbleController provideBubbleController(Context context,
            ShellController shellController,
            BubbleData data,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            UserManager userManager,
            LauncherApps launcherApps,
            TaskStackListenerImpl taskStackListener,
            BubbleLogger logger,
            ShellTaskOrganizer organizer,
            BubblePositioner positioner,
            DisplayController displayController,
            @DynamicOverride Optional<OneHandedController> oneHandedOptional,
            DragAndDropController dragAndDropController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            TaskViewTransitions taskViewTransitions,
            SyncTransactionQueue syncQueue) {
        return new BubbleController(context, shellController, data, null /* synchronizer */,
                floatingContentCoordinator,
                new BubbleDataRepository(context, launcherApps, mainExecutor),
                statusBarService, windowManager, windowManagerShellWrapper, userManager,
                launcherApps, logger, taskStackListener, organizer, positioner, displayController,
                oneHandedOptional, dragAndDropController, mainExecutor, mainHandler, bgExecutor,
                taskViewTransitions, syncQueue);
    }

    //
    // Window decoration
    //

    @WMSingleton
    @Provides
    static WindowDecorViewModel<?> provideWindowDecorViewModel(
            Context context,
            @ShellMainThread Handler mainHandler,
            @ShellMainThread Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue) {
        return new CaptionWindowDecorViewModel(
                        context,
                        mainHandler,
                        mainChoreographer,
                        taskOrganizer,
                        displayController,
                        syncQueue);
    }

    //
    // Freeform
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static FreeformComponents provideFreeformComponents(
            FreeformTaskListener<?> taskListener,
            FreeformTaskTransitionHandler transitionHandler) {
        return new FreeformComponents(taskListener, Optional.of(transitionHandler));
    }

    @WMSingleton
    @Provides
    static FreeformTaskListener<?> provideFreeformTaskListener(
            WindowDecorViewModel<?> windowDecorViewModel) {
        return new FreeformTaskListener<>(windowDecorViewModel);
    }

    @WMSingleton
    @Provides
    static FreeformTaskTransitionHandler provideTaskTransitionHandler(
            Transitions transitions,
            WindowDecorViewModel<?> windowDecorViewModel,
            FreeformTaskListener<?> freeformTaskListener) {
        return new FreeformTaskTransitionHandler(transitions, windowDecorViewModel,
                freeformTaskListener);
    }

    //
    // One handed mode
    //


    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    @DynamicOverride
    static OneHandedController provideOneHandedController(Context context,
            ShellController shellController,
            WindowManager windowManager, DisplayController displayController,
            DisplayLayout displayLayout, TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger, InteractionJankMonitor jankMonitor,
            @ShellMainThread ShellExecutor mainExecutor, @ShellMainThread Handler mainHandler) {
        return OneHandedController.create(context, shellController, windowManager,
                displayController, displayLayout, taskStackListener, jankMonitor, uiEventLogger,
                mainExecutor, mainHandler);
    }

    //
    // Splitscreen
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static SplitScreenController provideSplitScreenController(
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor,
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, Transitions transitions,
            TransactionPool transactionPool, IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks) {
        return new SplitScreenController(shellController, shellTaskOrganizer, syncQueue, context,
                rootTaskDisplayAreaOrganizer, mainExecutor, displayController, displayImeController,
                displayInsetsController, transitions, transactionPool, iconProvider,
                recentTasks);
    }

    //
    // Pip
    //

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context,
            ShellController shellController, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipKeepClearAlgorithm pipKeepClearAlgorithm, PipBoundsState pipBoundsState,
            PipMotionHelper pipMotionHelper, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTransitionState pipTransitionState,
            PipTouchHandler pipTouchHandler, PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            Optional<OneHandedController> oneHandedController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(PipController.create(context, shellController, displayController,
                pipAppOpsListener, pipBoundsAlgorithm, pipKeepClearAlgorithm, pipBoundsState,
                pipMotionHelper,
                pipMediaController, phonePipMenuController, pipTaskOrganizer, pipTransitionState,
                pipTouchHandler, pipTransitionController, windowManagerShellWrapper,
                taskStackListener, pipParamsChangedForwarder, oneHandedController, mainExecutor));
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context) {
        return new PipBoundsState(context);
    }

    @WMSingleton
    @Provides
    static PipSnapAlgorithm providePipSnapAlgorithm() {
        return new PipSnapAlgorithm();
    }

    @WMSingleton
    @Provides
    static PipKeepClearAlgorithm providePipKeepClearAlgorithm() {
        return new PipKeepClearAlgorithm();
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providesPipBoundsAlgorithm(Context context,
            PipBoundsState pipBoundsState, PipSnapAlgorithm pipSnapAlgorithm) {
        return new PipBoundsAlgorithm(context, pipBoundsState, pipSnapAlgorithm);
    }

    // Handler is used by Icon.loadDrawableAsync
    @WMSingleton
    @Provides
    static PhonePipMenuController providesPipPhoneMenuController(Context context,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            SystemWindows systemWindows,
            Optional<SplitScreenController> splitScreenOptional,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return new PhonePipMenuController(context, pipBoundsState, pipMediaController,
                systemWindows, splitScreenOptional, pipUiEventLogger, mainExecutor, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipTouchHandler providePipTouchHandler(Context context,
            PhonePipMenuController menuPhoneController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            PipMotionHelper pipMotionHelper,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTouchHandler(context, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, pipTaskOrganizer, pipMotionHelper,
                floatingContentCoordinator, pipUiEventLogger, mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipTransitionState providePipTransitionState() {
        return new PipTransitionState();
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            SyncTransactionQueue syncTransactionQueue,
            PipTransitionState pipTransitionState,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipMenuController menuPhoneController,
            PipAnimationController pipAnimationController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            PipTransitionController pipTransitionController,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            Optional<SplitScreenController> splitScreenControllerOptional,
            DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTaskOrganizer(context,
                syncTransactionQueue, pipTransitionState, pipBoundsState, pipBoundsAlgorithm,
                menuPhoneController, pipAnimationController, pipSurfaceTransactionHelper,
                pipTransitionController, pipParamsChangedForwarder, splitScreenControllerOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer, mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipAnimationController providePipAnimationController(PipSurfaceTransactionHelper
            pipSurfaceTransactionHelper) {
        return new PipAnimationController(pipSurfaceTransactionHelper);
    }

    @WMSingleton
    @Provides
    static PipTransitionController providePipTransitionController(Context context,
            Transitions transitions, ShellTaskOrganizer shellTaskOrganizer,
            PipAnimationController pipAnimationController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipTransitionState pipTransitionState,
            PhonePipMenuController pipMenuController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenOptional) {
        return new PipTransition(context, pipBoundsState, pipTransitionState, pipMenuController,
                pipBoundsAlgorithm, pipAnimationController, transitions, shellTaskOrganizer,
                pipSurfaceTransactionHelper, splitScreenOptional);
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            PipTouchHandler pipTouchHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipAppOpsListener(context, pipTouchHandler.getMotionHelper(), mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipMotionHelper providePipMotionHelper(Context context,
            PipBoundsState pipBoundsState, PipTaskOrganizer pipTaskOrganizer,
            PhonePipMenuController menuController, PipSnapAlgorithm pipSnapAlgorithm,
            PipTransitionController pipTransitionController,
            FloatingContentCoordinator floatingContentCoordinator) {
        return new PipMotionHelper(context, pipBoundsState, pipTaskOrganizer,
                menuController, pipSnapAlgorithm, pipTransitionController,
                floatingContentCoordinator);
    }

    //
    // Unfold transition
    //
    @WMSingleton
    @Provides
    @DynamicOverride
    static UnfoldAnimationController provideUnfoldAnimationController(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            TransactionPool transactionPool,
            @UnfoldTransition SplitTaskUnfoldAnimator splitAnimator,
            FullscreenUnfoldTaskAnimator fullscreenAnimator,
            Lazy<Optional<UnfoldTransitionHandler>> unfoldTransitionHandler,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        final List<UnfoldTaskAnimator> animators = new ArrayList<>();
        animators.add(splitAnimator);
        animators.add(fullscreenAnimator);

        return new UnfoldAnimationController(
                        transactionPool,
                        progressProvider.get(),
                        animators,
                        unfoldTransitionHandler,
                        mainExecutor
                );
    }


    @Provides
    static FullscreenUnfoldTaskAnimator provideFullscreenUnfoldTaskAnimator(
            Context context,
            UnfoldBackgroundController unfoldBackgroundController,
            DisplayInsetsController displayInsetsController
    ) {
        return new FullscreenUnfoldTaskAnimator(context, unfoldBackgroundController,
                displayInsetsController);
    }

    @Provides
    static SplitTaskUnfoldAnimator provideSplitTaskUnfoldAnimatorBase(
            Context context,
            UnfoldBackgroundController backgroundController,
            @ShellMainThread ShellExecutor executor,
            Lazy<Optional<SplitScreenController>> splitScreenOptional,
            DisplayInsetsController displayInsetsController
    ) {
        return new SplitTaskUnfoldAnimator(context, executor, splitScreenOptional,
                backgroundController, displayInsetsController);
    }

    @WMSingleton
    @UnfoldShellTransition
    @Binds
    abstract SplitTaskUnfoldAnimator provideShellSplitTaskUnfoldAnimator(
            SplitTaskUnfoldAnimator splitTaskUnfoldAnimator);

    @WMSingleton
    @UnfoldTransition
    @Binds
    abstract SplitTaskUnfoldAnimator provideSplitTaskUnfoldAnimator(
            SplitTaskUnfoldAnimator splitTaskUnfoldAnimator);

    @WMSingleton
    @Provides
    @DynamicOverride
    static UnfoldTransitionHandler provideUnfoldTransitionHandler(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            FullscreenUnfoldTaskAnimator animator,
            @UnfoldShellTransition SplitTaskUnfoldAnimator unfoldAnimator,
            TransactionPool transactionPool,
            Transitions transitions,
            @ShellMainThread ShellExecutor executor) {
        return new UnfoldTransitionHandler(progressProvider.get(), animator,
                unfoldAnimator, transactionPool, executor, transitions);
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

    @WMSingleton
    @Provides
    static PipParamsChangedForwarder providePipParamsChangedForwarder() {
        return new PipParamsChangedForwarder();
    }
}
