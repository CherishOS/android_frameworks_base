/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import android.app.ActivityManager.TaskDescription;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IApplicationToken;
import org.junit.Assert;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

import android.app.ActivityManager.TaskSnapshot;
import android.content.Context;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static org.mockito.Mockito.mock;

import com.android.server.AttributeCache;

import java.util.HashSet;
import java.util.LinkedList;

/**
 * Common base class for window manager unit test classes.
 */
class WindowTestsBase {
    static WindowManagerService sWm = null;
    static TestWindowManagerPolicy sPolicy = null;
    private final static IWindow sIWindow = new TestIWindow();
    private final static Session sMockSession = mock(Session.class);
    // The default display is removed in {@link #setUp} and then we iterate over all displays to
    // make sure we don't collide with any existing display. If we run into no other display, the
    // added display should be treated as default.
    private static int sNextDisplayId = Display.DEFAULT_DISPLAY;
    static int sNextStackId = FIRST_DYNAMIC_STACK_ID;
    private static int sNextTaskId = 0;

    private static boolean sOneTimeSetupDone = false;
    static DisplayContent sDisplayContent;
    static DisplayInfo sDisplayInfo = new DisplayInfo();
    static WindowLayersController sLayersController;
    static WindowState sWallpaperWindow;
    static WindowState sImeWindow;
    static WindowState sImeDialogWindow;
    static WindowState sStatusBarWindow;
    static WindowState sDockedDividerWindow;
    static WindowState sNavBarWindow;
    static WindowState sAppWindow;
    static WindowState sChildAppWindowAbove;
    static WindowState sChildAppWindowBelow;
    static HashSet<WindowState> sCommonWindows;

    @Before
    public void setUp() throws Exception {
        if (sOneTimeSetupDone) {
            return;
        }
        sOneTimeSetupDone = true;
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getTargetContext();
        AttributeCache.init(context);
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        sPolicy = (TestWindowManagerPolicy) sWm.mPolicy;
        sLayersController = new WindowLayersController(sWm);
        sDisplayContent = sWm.mRoot.getDisplayContent(context.getDisplay().getDisplayId());
        if (sDisplayContent != null) {
            sDisplayContent.removeImmediately();
        }
        // Make sure that display ids don't overlap, so there won't be several displays with same
        // ids among RootWindowContainer children.
        for (DisplayContent dc : sWm.mRoot.mChildren) {
            if (dc.getDisplayId() >= sNextDisplayId) {
                sNextDisplayId = dc.getDisplayId() + 1;
            }
        }
        context.getDisplay().getDisplayInfo(sDisplayInfo);
        sDisplayContent = createNewDisplay();
        sWm.mDisplayEnabled = true;
        sWm.mDisplayReady = true;

        // Set-up some common windows.
        sCommonWindows = new HashSet();
        sWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
        sImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "sImeWindow");
        sWm.mInputMethodWindow = sImeWindow;
        sImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG, "sImeDialogWindow");
        sStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "sStatusBarWindow");
        sNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "sNavBarWindow");
        sDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER, "sDockedDividerWindow");
        sAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "sAppWindow");
        sChildAppWindowAbove = createCommonWindow(sAppWindow, TYPE_APPLICATION_ATTACHED_DIALOG,
                "sChildAppWindowAbove");
        sChildAppWindowBelow = createCommonWindow(sAppWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                "sChildAppWindowBelow");
    }

    @After
    public void tearDown() throws Exception {
        final LinkedList<WindowState> nonCommonWindows = new LinkedList();
        sWm.mRoot.forAllWindows(w -> {
            if (!sCommonWindows.contains(w)) {
                nonCommonWindows.addLast(w);
            }
        }, true /* traverseTopToBottom */);

        while (!nonCommonWindows.isEmpty()) {
            nonCommonWindows.pollLast().removeImmediately();
        }

        sWm.mInputMethodTarget = null;
    }

    private static WindowState createCommonWindow(WindowState parent, int type, String name) {
        final WindowState win = createWindow(parent, type, name);
        sCommonWindows.add(win);
        // Prevent common windows from been IMe targets
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertGreaterThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be greater than " + second, first > second);
    }

    /**
     * Waits until the main handler for WM has processed all messages.
     */
    void waitUntilHandlerIdle() {
        sWm.mH.runWithScissors(() -> { }, 0);
    }

    private static WindowToken createWindowToken(DisplayContent dc, int stackId, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new TestWindowToken(type, dc);
        }

        final TaskStack stack = stackId == INVALID_STACK_ID
                ? createTaskStackOnDisplay(dc)
                : createStackControllerOnStackOnDisplay(stackId, dc).mContainer;
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final TestAppWindowToken token = new TestAppWindowToken(dc);
        task.addChild(token, 0);
        return token;
    }

    static WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, sDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    static WindowState createWindowOnStack(WindowState parent, int stackId, int type,
            DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, stackId, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final AppWindowToken token = new TestAppWindowToken(sDisplayContent);
        task.addChild(token, 0);
        return createWindow(null, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, INVALID_STACK_ID, type);
        return createWindow(parent, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowToken token = createWindowToken(dc, INVALID_STACK_ID, type);
        return createWindow(parent, type, token, name, ownerCanAddInternalSystemWindow);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        return createWindow(parent, type, token, name, false /* ownerCanAddInternalSystemWindow */);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(sWm, sMockSession, sIWindow, token, parent, OP_NONE,
                0, attrs, 0, 0, ownerCanAddInternalSystemWindow);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    /** Creates a {@link TaskStack} and adds it to the specified {@link DisplayContent}. */
    static TaskStack createTaskStackOnDisplay(DisplayContent dc) {
        return createStackControllerOnDisplay(dc).mContainer;
    }

    static StackWindowController createStackControllerOnDisplay(DisplayContent dc) {
        final int stackId = ++sNextStackId;
        return createStackControllerOnStackOnDisplay(stackId, dc);
    }

    static StackWindowController createStackControllerOnStackOnDisplay(int stackId,
            DisplayContent dc) {
        return new StackWindowController(stackId, null, dc.getDisplayId(),
                true /* onTop */, new Rect(), sWm);
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    static Task createTaskInStack(TaskStack stack, int userId) {
        final Task newTask = new Task(sNextTaskId++, stack, userId, sWm, null, EMPTY, 0, false,
                false, new TaskDescription(), null);
        stack.addTask(newTask, POSITION_TOP);
        return newTask;
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay() {
        final int displayId = sNextDisplayId++;
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                sDisplayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
        return new DisplayContent(display, sWm, sLayersController, new WallpaperController(sWm));
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    static class TestWindowToken extends WindowToken {
        int adj = 0;

        TestWindowToken(int type, DisplayContent dc) {
            this(type, dc, false /* persistOnEmpty */);
        }

        TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(sWm, mock(IBinder.class), type, persistOnEmpty, dc,
                    false /* ownerCanManageAppTokens */);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }

        @Override
        int getAnimLayerAdjustment() {
            return adj;
        }
    }

    /** Used so we can gain access to some protected members of the {@link AppWindowToken} class. */
    static class TestAppWindowToken extends AppWindowToken {

        TestAppWindowToken(DisplayContent dc) {
            super(sWm, null, false, dc, true /* fillsParent */);
        }

        TestAppWindowToken(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller) {
            super(service, token, voiceInteraction, dc, inputDispatchingTimeoutNanos, fullscreen,
                    showForAllUsers, targetSdk, orientation, rotationAnimationHint, configChanges,
                    launchTaskBehind, alwaysFocusable, controller);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }

        WindowState getFirstChild() {
            return mChildren.getFirst();
        }

        WindowState getLastChild() {
            return mChildren.getLast();
        }

        int positionInParent() {
            return getParent().mChildren.indexOf(this);
        }
    }

    /* Used so we can gain access to some protected members of the {@link Task} class */
    class TestTask extends Task {

        boolean mShouldDeferRemoval = false;
        boolean mOnDisplayChangedCalled = false;
        private boolean mUseLocalIsAnimating = false;
        private boolean mIsAnimating = false;

        TestTask(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
                Configuration overrideConfig, int resizeMode, boolean supportsPictureInPicture,
                boolean homeTask, TaskWindowContainerController controller) {
            super(taskId, stack, userId, service, bounds, overrideConfig, resizeMode,
                    supportsPictureInPicture, homeTask, new TaskDescription(), controller);
        }

        boolean shouldDeferRemoval() {
            return mShouldDeferRemoval;
        }

        int positionInParent() {
            return getParent().mChildren.indexOf(this);
        }

        @Override
        void onDisplayChanged(DisplayContent dc) {
            super.onDisplayChanged(dc);
            mOnDisplayChangedCalled = true;
        }

        @Override
        boolean isAnimating() {
            return mUseLocalIsAnimating ? mIsAnimating : super.isAnimating();
        }

        void setLocalIsAnimating(boolean isAnimating) {
            mUseLocalIsAnimating = true;
            mIsAnimating = isAnimating;
        }
    }

    /**
     * Used so we can gain access to some protected members of {@link TaskWindowContainerController}
     * class.
     */
    class TestTaskWindowContainerController extends TaskWindowContainerController {

        TestTaskWindowContainerController() {
            this(createStackControllerOnDisplay(sDisplayContent));
        }

        TestTaskWindowContainerController(StackWindowController stackController) {
            super(sNextTaskId++, new TaskWindowContainerListener() {
                        @Override
                        public void onSnapshotChanged(TaskSnapshot snapshot) {

                        }

                        @Override
                        public void requestResize(Rect bounds, int resizeMode) {

                        }
                    }, stackController, 0 /* userId */, null /* bounds */,
                    EMPTY /* overrideConfig*/, RESIZE_MODE_UNRESIZEABLE,
                    false /* supportsPictureInPicture */, false /* homeTask*/, true /* toTop*/,
                    true /* showForAllUsers */, new TaskDescription(), sWm);
        }

        @Override
        TestTask createTask(int taskId, TaskStack stack, int userId, Rect bounds,
                Configuration overrideConfig, int resizeMode, boolean supportsPictureInPicture,
                boolean homeTask, TaskDescription taskDescription) {
            return new TestTask(taskId, stack, userId, mService, bounds, overrideConfig, resizeMode,
                    supportsPictureInPicture, homeTask, this);
        }
    }

    class TestAppWindowContainerController extends AppWindowContainerController {

        final IApplicationToken mToken;

        TestAppWindowContainerController(TestTaskWindowContainerController taskController) {
            this(taskController, new TestIApplicationToken());
        }

        TestAppWindowContainerController(TestTaskWindowContainerController taskController,
                IApplicationToken token) {
            super(taskController, token, null /* listener */, 0 /* index */,
                    SCREEN_ORIENTATION_UNSPECIFIED, true /* fullscreen */,
                    true /* showForAllUsers */, 0 /* configChanges */, false /* voiceInteraction */,
                    false /* launchTaskBehind */, false /* alwaysFocusable */,
                    0 /* targetSdkVersion */, 0 /* rotationAnimationHint */,
                    0 /* inputDispatchingTimeoutNanos */, sWm);
            mToken = token;
        }

        @Override
        AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller) {
            return new TestAppWindowToken(service, token, voiceInteraction, dc,
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk,
                    orientation,
                    rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable,
                    controller);
        }

        AppWindowToken getAppWindowToken() {
            return (AppWindowToken) sDisplayContent.getWindowToken(mToken.asBinder());
        }
    }

    class TestIApplicationToken implements IApplicationToken {

        private final Binder mBinder = new Binder();
        @Override
        public IBinder asBinder() {
            return mBinder;
        }
    }

    /** Used to track resize reports. */
    class TestWindowState extends WindowState {
        boolean resizeReported;

        TestWindowState(WindowManager.LayoutParams attrs, WindowToken token) {
            super(sWm, sMockSession, sIWindow, token, null, OP_NONE, 0, attrs, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            resizeReported = true;
        }

        @Override
        public boolean isGoneForLayoutLw() {
            return false;
        }

        @Override
        void updateResizingWindowIfNeeded() {
            // Used in AppWindowTokenTests#testLandscapeSeascapeRotationRelayout to deceive
            // the system that it can actually update the window.
            boolean hadSurface = mHasSurface;
            mHasSurface = true;

            super.updateResizingWindowIfNeeded();

            mHasSurface = hadSurface;
        }
    }
}
