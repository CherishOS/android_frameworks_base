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

package com.android.wm.shell.startingsurface;

import static android.content.Context.CONTEXT_RESTRICTED;
import static android.content.res.Configuration.EMPTY;
import static android.view.Choreographer.CALLBACK_INSETS_ANIMATION;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.StartingWindowInfo;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A class which able to draw splash screen or snapshot as the starting window for a task.
 *
 * In order to speed up, there will use two threads to creating a splash screen in parallel.
 * Right now we are still using PhoneWindow to create splash screen window, so the view is added to
 * the ViewRootImpl, and those view won't be draw immediately because the ViewRootImpl will call
 * scheduleTraversal to register a callback from Choreographer, so the drawing result of the view
 * can synchronize on each frame.
 *
 * The bad thing is that we cannot decide when would Choreographer#doFrame happen, and drawing
 * the AdaptiveIconDrawable object can be time consuming, so we use the splash-screen background
 * thread to draw the AdaptiveIconDrawable object to a Bitmap and cache it to a BitmapShader after
 * the SplashScreenView just created, once we get the BitmapShader then the #draw call can be very
 * quickly.
 *
 * So basically we are using the spare time to prepare the SplashScreenView while splash screen
 * thread is waiting for
 * 1. WindowManager#addView(binder call to WM),
 * 2. Choreographer#doFrame happen(uncertain time for next frame, depends on device),
 * 3. Session#relayout(another binder call to WM which under Choreographer#doFrame, but will
 * always happen before #draw).
 * Because above steps are running on splash-screen thread, so pre-draw the BitmapShader on
 * splash-screen background tread can make they execute in parallel, which ensure it is faster then
 * to draw the AdaptiveIconDrawable when receive callback from Choreographer#doFrame.
 *
 * Here is the sequence to compare the difference between using single and two thread.
 *
 * Single thread:
 * => makeSplashScreenContentView -> WM#addView .. waiting for Choreographer#doFrame -> relayout
 * -> draw -> AdaptiveIconDrawable#draw
 *
 * Two threads:
 * => makeSplashScreenContentView -> cachePaint(=AdaptiveIconDrawable#draw)
 * => WM#addView -> .. waiting for Choreographer#doFrame -> relayout -> draw -> (draw the Paint
 * directly).
 *
 * @hide
 */
public class StartingSurfaceDrawer {
    static final String TAG = StartingSurfaceDrawer.class.getSimpleName();
    static final boolean DEBUG_SPLASH_SCREEN = StartingWindowController.DEBUG_SPLASH_SCREEN;
    static final boolean DEBUG_TASK_SNAPSHOT = StartingWindowController.DEBUG_TASK_SNAPSHOT;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final ShellExecutor mSplashScreenExecutor;
    private final SplashscreenContentDrawer mSplashscreenContentDrawer;
    private Choreographer mChoreographer;

    /**
     * @param splashScreenExecutor The thread used to control add and remove starting window.
     */
    public StartingSurfaceDrawer(Context context, ShellExecutor splashScreenExecutor,
            TransactionPool pool) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mSplashScreenExecutor = splashScreenExecutor;
        final int maxAnimatableIconDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.max_starting_window_intro_icon_anim_duration);
        final int iconExitAnimDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.starting_window_icon_exit_anim_duration);
        final int appRevealAnimDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.starting_window_app_reveal_anim_duration);
        mSplashscreenContentDrawer = new SplashscreenContentDrawer(mContext,
                maxAnimatableIconDuration, iconExitAnimDuration, appRevealAnimDuration, pool);
        mSplashScreenExecutor.execute(() -> mChoreographer = Choreographer.getInstance());
    }

    private final SparseArray<StartingWindowRecord> mStartingWindowRecords = new SparseArray<>();

    /** Obtain proper context for showing splash screen on the provided display. */
    private Context getDisplayContext(Context context, int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            // The default context fits.
            return context;
        }

        final Display targetDisplay = mDisplayManager.getDisplay(displayId);
        if (targetDisplay == null) {
            // Failed to obtain the non-default display where splash screen should be shown,
            // lets not show at all.
            return null;
        }

        return context.createDisplayContext(targetDisplay);
    }

    /**
     * Called when a task need a splash screen starting window.
     * @param emptyView Whether drawing an empty frame without anything on it.
     */
    void addSplashScreenStartingWindow(StartingWindowInfo windowInfo, IBinder appToken,
            boolean emptyView) {
        final RunningTaskInfo taskInfo = windowInfo.taskInfo;
        final ActivityInfo activityInfo = taskInfo.topActivityInfo;
        if (activityInfo == null) {
            return;
        }

        final int displayId = taskInfo.displayId;
        if (activityInfo.packageName == null) {
            return;
        }

        CharSequence nonLocalizedLabel = activityInfo.nonLocalizedLabel;
        int labelRes = activityInfo.labelRes;
        if (activityInfo.nonLocalizedLabel == null && activityInfo.labelRes == 0) {
            ApplicationInfo app = activityInfo.applicationInfo;
            nonLocalizedLabel = app.nonLocalizedLabel;
            labelRes = app.labelRes;
        }

        final int taskId = taskInfo.taskId;
        Context context = mContext;
        // replace with the default theme if the application didn't set
        final int theme = windowInfo.splashScreenThemeResId != 0
                ? windowInfo.splashScreenThemeResId
                : activityInfo.getThemeResource() != 0 ? activityInfo.getThemeResource()
                        : com.android.internal.R.style.Theme_DeviceDefault_DayNight;
        if (DEBUG_SPLASH_SCREEN) {
            Slog.d(TAG, "addSplashScreen " + activityInfo.packageName
                    + ": nonLocalizedLabel=" + nonLocalizedLabel + " theme="
                    + Integer.toHexString(theme) + " task= " + taskInfo.taskId);
        }

        // Obtain proper context to launch on the right display.
        final Context displayContext = getDisplayContext(context, displayId);
        if (displayContext == null) {
            // Can't show splash screen on requested display, so skip showing at all.
            return;
        }
        context = displayContext;
        if (theme != context.getThemeResId() || labelRes != 0) {
            try {
                context = context.createPackageContextAsUser(activityInfo.packageName,
                        CONTEXT_RESTRICTED, UserHandle.of(taskInfo.userId));
                context.setTheme(theme);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Failed creating package context with package name "
                        + activityInfo.packageName + " for user " + taskInfo.userId, e);
                return;
            }
        }

        final Configuration taskConfig = taskInfo.getConfiguration();
        if (taskConfig != null && !taskConfig.equals(EMPTY)) {
            if (DEBUG_SPLASH_SCREEN) {
                Slog.d(TAG, "addSplashScreen: creating context based"
                        + " on task Configuration " + taskConfig + " for splash screen");
            }
            final Context overrideContext = context.createConfigurationContext(taskConfig);
            overrideContext.setTheme(theme);
            final TypedArray typedArray = overrideContext.obtainStyledAttributes(
                    com.android.internal.R.styleable.Window);
            final int resId = typedArray.getResourceId(R.styleable.Window_windowBackground, 0);
            try {
                if (resId != 0 && overrideContext.getDrawable(resId) != null) {
                    // We want to use the windowBackground for the override context if it is
                    // available, otherwise we use the default one to make sure a themed starting
                    // window is displayed for the app.
                    if (DEBUG_SPLASH_SCREEN) {
                        Slog.d(TAG, "addSplashScreen: apply overrideConfig"
                                + taskConfig + " to starting window resId=" + resId);
                    }
                    context = overrideContext;
                }
            } catch (Resources.NotFoundException e) {
                Slog.w(TAG, "failed creating starting window for overrideConfig at taskId: "
                        + taskId, e);
                return;
            }
            typedArray.recycle();
        }

        int windowFlags = 0;
        windowFlags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        final boolean[] showWallpaper = new boolean[1];
        final int[] splashscreenContentResId = new int[1];
        getWindowResFromContext(context, a -> {
            splashscreenContentResId[0] =
                    a.getResourceId(R.styleable.Window_windowSplashscreenContent, 0);
            showWallpaper[0] = a.getBoolean(R.styleable.Window_windowShowWallpaper, false);
        });
        if (showWallpaper[0]) {
            windowFlags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        final PhoneWindow win = new PhoneWindow(context);
        win.setIsStartingWindow(true);

        CharSequence label = context.getResources().getText(labelRes, null);
        // Only change the accessibility title if the label is localized
        if (label != null) {
            win.setTitle(label, true);
        } else {
            win.setTitle(nonLocalizedLabel, false);
        }

        win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);

        // Assumes it's safe to show starting windows of launched apps while
        // the keyguard is being hidden. This is okay because starting windows never show
        // secret information.
        // TODO(b/113840485): Occluded may not only happen on default display
        if (displayId == DEFAULT_DISPLAY && windowInfo.isKeyguardOccluded) {
            windowFlags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        }

        // Force the window flags: this is a fake window, so it is not really
        // touchable or focusable by the user.  We also add in the ALT_FOCUSABLE_IM
        // flag because we do know that the next window will take input
        // focus, so we want to get the IME window up on top of us right away.
        win.setFlags(windowFlags
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                windowFlags
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        final int iconRes = activityInfo.getIconResource();
        final int logoRes = activityInfo.getLogoResource();
        win.setDefaultIcon(iconRes);
        win.setDefaultLogo(logoRes);

        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);

        final WindowManager.LayoutParams params = win.getAttributes();
        params.token = appToken;
        params.packageName = activityInfo.packageName;
        params.windowAnimations = win.getWindowStyle().getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.format = PixelFormat.RGBA_8888;

        final Resources res = context.getResources();
        final boolean supportsScreen = res != null && (res.getCompatibilityInfo() != null
                && res.getCompatibilityInfo().supportsScreen());
        if (!supportsScreen) {
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
        }

        params.setTitle("Splash Screen " + activityInfo.packageName);

        // TODO(b/173975965) tracking performance
        // Prepare the splash screen content view on splash screen worker thread in parallel, so the
        // content view won't be blocked by binder call like addWindow and relayout.
        // 1. Trigger splash screen worker thread to create SplashScreenView before/while
        // Session#addWindow.
        // 2. Synchronize the SplashscreenView to splash screen thread before Choreographer start
        // traversal, which will call Session#relayout on splash screen thread.
        // 3. Pre-draw the BitmapShader if the icon is immobile on splash screen worker thread, at
        // the same time the splash screen thread should be executing Session#relayout. Blocking the
        // traversal -> draw on splash screen thread until the BitmapShader of the icon is ready.

        // Record whether create splash screen view success, notify to current thread after
        // create splash screen view finished.
        final SplashScreenViewSupplier viewSupplier = new SplashScreenViewSupplier();
        final Runnable setViewSynchronized = () -> {
            // waiting for setContentView before relayoutWindow
            SplashScreenView contentView = viewSupplier.get();
            final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
            // if record == null, either the starting window added fail or removed already.
            if (record != null) {
                // if view == null then creation of content view was failed.
                if (contentView != null) {
                    try {
                        win.setContentView(contentView);
                        contentView.cacheRootWindow(win);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "failed set content view to starting window "
                                + "at taskId: " + taskId, e);
                        contentView = null;
                    }
                }
                record.setSplashScreenView(contentView);
            }
        };
        mSplashscreenContentDrawer.createContentView(context, emptyView,
                splashscreenContentResId[0], activityInfo, taskId, viewSupplier::setView);

        try {
            final View view = win.getDecorView();
            final WindowManager wm = mContext.getSystemService(WindowManager.class);
            postAddWindow(taskId, appToken, view, wm, params);

            // We use the splash screen worker thread to create SplashScreenView while adding the
            // window, as otherwise Choreographer#doFrame might be delayed on this thread.
            // And since Choreographer#doFrame won't happen immediately after adding the window, if
            // the view is not added to the PhoneWindow on the first #doFrame, the view will not be
            // rendered on the first frame. So here we need to synchronize the view on the window
            // before first round relayoutWindow, which will happen after insets animation.
            mChoreographer.postCallback(CALLBACK_INSETS_ANIMATION, setViewSynchronized, null);
        } catch (RuntimeException e) {
            // don't crash if something else bad happens, for example a
            // failure loading resources because we are loading from an app
            // on external storage that has been unmounted.
            Slog.w(TAG, "failed creating starting window at taskId: " + taskId, e);
        }
    }

    private static class SplashScreenViewSupplier implements Supplier<SplashScreenView> {
        private SplashScreenView mView;
        private boolean mIsViewSet;
        void setView(SplashScreenView view) {
            synchronized (this) {
                mView = view;
                mIsViewSet = true;
                notify();
            }
        }

        @Override
        public @Nullable SplashScreenView get() {
            synchronized (this) {
                while (!mIsViewSet) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                return mView;
            }
        }
    }

    /**
     * Called when a task need a snapshot starting window.
     */
    void makeTaskSnapshotWindow(StartingWindowInfo startingWindowInfo, IBinder appToken,
            TaskSnapshot snapshot) {
        final int taskId = startingWindowInfo.taskInfo.taskId;
        final TaskSnapshotWindow surface = TaskSnapshotWindow.create(startingWindowInfo, appToken,
                snapshot, mSplashScreenExecutor, () -> removeWindowNoAnimate(taskId));
        final StartingWindowRecord tView = new StartingWindowRecord(null/* decorView */, surface);
        mStartingWindowRecords.put(taskId, tView);
    }

    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
            Slog.d(TAG, "Task start finish, remove starting surface for task " + taskId);
        }
        removeWindowSynced(taskId, leash, frame, playRevealAnimation);
    }

    /**
     * Called when the Task wants to copy the splash screen.
     * @param taskId
     */
    public void copySplashScreenView(int taskId) {
        final StartingWindowRecord preView = mStartingWindowRecords.get(taskId);
        SplashScreenViewParcelable parcelable;
        if (preView != null && preView.mContentView != null
                && preView.mContentView.isCopyable()) {
            parcelable = new SplashScreenViewParcelable(preView.mContentView);
        } else {
            parcelable = null;
        }
        if (DEBUG_SPLASH_SCREEN) {
            Slog.v(TAG, "Copying splash screen window view for task: " + taskId
                    + " parcelable? " + parcelable);
        }
        ActivityTaskManager.getInstance().onSplashScreenViewCopyFinished(taskId, parcelable);
    }

    protected void postAddWindow(int taskId, IBinder appToken, View view, WindowManager wm,
            WindowManager.LayoutParams params) {
        boolean shouldSaveView = true;
        try {
            wm.addView(view, params);
        } catch (WindowManager.BadTokenException e) {
            // ignore
            Slog.w(TAG, appToken + " already running, starting window not displayed. "
                    + e.getMessage());
            shouldSaveView = false;
        } finally {
            if (view != null && view.getParent() == null) {
                Slog.w(TAG, "view not successfully added to wm, removing view");
                wm.removeViewImmediate(view);
                shouldSaveView = false;
            }
        }
        if (shouldSaveView) {
            removeWindowNoAnimate(taskId);
            saveSplashScreenRecord(taskId, view);
        }
    }

    private void saveSplashScreenRecord(int taskId, View view) {
        final StartingWindowRecord tView = new StartingWindowRecord(view,
                null/* TaskSnapshotWindow */);
        mStartingWindowRecords.put(taskId, tView);
    }

    private void removeWindowNoAnimate(int taskId) {
        removeWindowSynced(taskId, null, null, false);
    }

    protected void removeWindowSynced(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
        if (record != null) {
            if (record.mDecorView != null) {
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.v(TAG, "Removing splash screen window for task: " + taskId);
                }
                if (record.mContentView != null) {
                    if (leash != null || playRevealAnimation) {
                        mSplashscreenContentDrawer.applyExitAnimation(record.mContentView,
                                leash, frame, record.isEarlyExit(),
                                () -> removeWindowInner(record.mDecorView, true));
                    } else {
                        // TODO(183004107) Always hide decorView when playRevealAnimation is enabled
                        //  from TaskOrganizerController#removeStartingWindow
                        // the SplashScreenView has been copied to client, skip default exit
                        // animation
                        removeWindowInner(record.mDecorView, false);
                    }
                } else {
                    // no animation will be applied
                    removeWindowInner(record.mDecorView, false);
                }
            }
            if (record.mTaskSnapshotWindow != null) {
                if (DEBUG_TASK_SNAPSHOT) {
                    Slog.v(TAG, "Removing task snapshot window for " + taskId);
                }
                record.mTaskSnapshotWindow.remove();
            }
            mStartingWindowRecords.remove(taskId);
        }
    }

    private void removeWindowInner(View decorView, boolean hideView) {
        if (hideView) {
            decorView.setVisibility(View.GONE);
        }
        final WindowManager wm = decorView.getContext().getSystemService(WindowManager.class);
        if (wm != null) {
            wm.removeView(decorView);
        }
    }

    private void getWindowResFromContext(Context ctx, Consumer<TypedArray> consumer) {
        final TypedArray a = ctx.obtainStyledAttributes(R.styleable.Window);
        consumer.accept(a);
        a.recycle();
    }

    /**
     * Record the view or surface for a starting window.
     */
    private static class StartingWindowRecord {
        private static final long EARLY_START_MINIMUM_TIME_MS = 250;
        private final View mDecorView;
        private final TaskSnapshotWindow mTaskSnapshotWindow;
        private SplashScreenView mContentView;
        private boolean mSetSplashScreen;
        private long mContentCreateTime;

        StartingWindowRecord(View decorView, TaskSnapshotWindow taskSnapshotWindow) {
            mDecorView = decorView;
            mTaskSnapshotWindow = taskSnapshotWindow;
        }

        private void setSplashScreenView(SplashScreenView splashScreenView) {
            if (mSetSplashScreen) {
                return;
            }
            mContentView = splashScreenView;
            mContentCreateTime = SystemClock.uptimeMillis();
            mSetSplashScreen = true;
        }

        boolean isEarlyExit() {
            return SystemClock.uptimeMillis() - mContentCreateTime < EARLY_START_MINIMUM_TIME_MS;
        }
    }
}
