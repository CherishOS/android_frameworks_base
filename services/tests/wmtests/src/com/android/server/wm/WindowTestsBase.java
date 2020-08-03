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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Process.SYSTEM_UID;
import static android.view.View.VISIBLE;
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
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;
import android.window.ITaskOrganizer;
import android.window.WindowContainerToken;

import com.android.internal.util.ArrayUtils;
import com.android.server.AttributeCache;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.Description;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Common base class for window manager unit test classes. */
class WindowTestsBase extends SystemServiceTestsBase {
    final Context mContext = getInstrumentation().getTargetContext();

    // Default package name
    static final String DEFAULT_COMPONENT_PACKAGE_NAME = "com.foo";

    // Default base activity name
    private static final String DEFAULT_COMPONENT_CLASS_NAME = ".BarActivity";

    ActivityTaskManagerService mAtm;
    RootWindowContainer mRootWindowContainer;
    ActivityStackSupervisor mSupervisor;
    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;

    DisplayInfo mDisplayInfo = new DisplayInfo();
    DisplayContent mDefaultDisplay;

    /**
     * It is {@link #mDefaultDisplay} by default. If the test class or method is annotated with
     * {@link UseTestDisplay}, it will be an additional display.
     */
    DisplayContent mDisplayContent;

    // The following fields are only available depending on the usage of annotation UseTestDisplay.
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mNotificationShadeWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;

    /**
     * Spied {@link Transaction} class than can be used to verify calls.
     */
    Transaction mTransaction;

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        mAtm = mSystemServicesTestRule.getActivityTaskManagerService();
        mSupervisor = mAtm.mStackSupervisor;
        mRootWindowContainer = mAtm.mRootWindowContainer;
        mWm = mSystemServicesTestRule.getWindowManagerService();
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        mDefaultDisplay = mWm.mRoot.getDefaultDisplay();
        mTransaction = mSystemServicesTestRule.mTransaction;
        mMockSession = mock(Session.class);

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getDisplayInfo(mDisplayInfo);

        // Only create an additional test display for annotated test class/method because it may
        // significantly increase the execution time.
        final Description description = mSystemServicesTestRule.getDescription();
        UseTestDisplay testDisplayAnnotation = description.getAnnotation(UseTestDisplay.class);
        if (testDisplayAnnotation == null) {
            testDisplayAnnotation = description.getTestClass().getAnnotation(UseTestDisplay.class);
        }
        if (testDisplayAnnotation != null) {
            createTestDisplay(testDisplayAnnotation);
        } else {
            mDisplayContent = mDefaultDisplay;
        }
    }

    private void createTestDisplay(UseTestDisplay annotation) {
        beforeCreateTestDisplay();
        mDisplayContent = createNewDisplay(true /* supportIme */);

        final boolean addAll = annotation.addAllCommonWindows();
        final @CommonTypes int[] requestedWindows = annotation.addWindows();

        if (addAll || ArrayUtils.contains(requestedWindows, W_WALLPAPER)) {
            mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD)) {
            mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
            mDisplayContent.mInputMethodWindow = mImeWindow;
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD_DIALOG)) {
            mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                    "mImeDialogWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_STATUS_BAR)) {
            mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NOTIFICATION_SHADE)) {
            mNotificationShadeWindow = createCommonWindow(null, TYPE_NOTIFICATION_SHADE,
                    "mNotificationShadeWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NAVIGATION_BAR)) {
            mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_DOCK_DIVIDER)) {
            mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                    "mDockedDividerWindow");
        }
        final boolean addAboveApp = ArrayUtils.contains(requestedWindows, W_ABOVE_ACTIVITY);
        final boolean addBelowApp = ArrayUtils.contains(requestedWindows, W_BELOW_ACTIVITY);
        if (addAll || addAboveApp || addBelowApp
                || ArrayUtils.contains(requestedWindows, W_ACTIVITY)) {
            mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
        }
        if (addAll || addAboveApp) {
            mChildAppWindowAbove = createCommonWindow(mAppWindow, TYPE_APPLICATION_ATTACHED_DIALOG,
                    "mChildAppWindowAbove");
        }
        if (addAll || addBelowApp) {
            mChildAppWindowBelow = createCommonWindow(mAppWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                    "mChildAppWindowBelow");
        }

        mDisplayContent.getInsetsPolicy().setRemoteInsetsControllerControlsSystemBars(false);

        // Adding a display will cause freezing the display. Make sure to wait until it's
        // unfrozen to not run into race conditions with the tests.
        waitUntilHandlersIdle();
    }

    void beforeCreateTestDisplay() {
        // Called before display is created.
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        final WindowState win = createWindow(parent, type, name);
        // Prevent common windows from been IME targets.
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return WindowTestUtils.createTestWindowToken(type, dc);
        }

        return createActivityRecord(dc, windowingMode, activityType);
    }

    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode, int activityType) {
        return createTestActivityRecord(dc, windowingMode, activityType);
    }

    ActivityRecord createTestActivityRecord(DisplayContent dc, int
            windowingMode, int activityType) {
        final Task stack = createTaskStackOnDisplay(windowingMode, activityType, dc);
        return WindowTestUtils.createTestActivityRecord(stack);
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    WindowState createWindow(WindowState parent, int type, String name, int ownerId) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name, ownerId)
                : createWindow(parent, type, parent.mToken, name, ownerId);
    }

    WindowState createWindowOnStack(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final ActivityRecord activity =
                WindowTestUtils.createTestActivityRecord(task.getDisplayContent());
        task.addChild(activity, 0);
        return createWindow(null, type, activity, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            int ownerId) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, ownerId);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                ownerCanAddInternalSystemWindow);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId) {
        return createWindow(parent, type, token, name, ownerId,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, UserHandle.getUserId(ownerId),
                ownerCanAddInternalSystemWindow, mWm, mMockSession, mIWindow,
                mSystemServicesTestRule.getPowerManagerWrapper());
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, int userId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow,
            WindowState.PowerManagerWrapper powerManagerWrapper) {
        SystemServicesTestRule.checkHoldsLock(service.mGlobalLock);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(service, session, iWindow, token, parent,
                OP_NONE, 0, attrs, VISIBLE, ownerId, userId,
                ownerCanAddInternalSystemWindow,
                powerManagerWrapper);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    static void makeWindowVisible(WindowState... windows) {
        for (WindowState win : windows) {
            win.mViewVisibility = View.VISIBLE;
            win.mRelayoutCalled = true;
            win.mHasSurface = true;
            win.mHidden = false;
            win.showLw(false /* doAnimation */, false /* requestAnim */);
        }
    }

    /** Creates a {@link TaskDisplayArea} right above the default one. */
    static TaskDisplayArea createTaskDisplayArea(DisplayContent displayContent,
            WindowManagerService service, String name, int displayAreaFeature) {
        final TaskDisplayArea newTaskDisplayArea = new TaskDisplayArea(
                displayContent, service, name, displayAreaFeature);
        final TaskDisplayArea defaultTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();

        // Insert the new TDA to the correct position.
        defaultTaskDisplayArea.getParent().addChild(newTaskDisplayArea,
                defaultTaskDisplayArea.getParent().mChildren.indexOf(defaultTaskDisplayArea)
                        + 1);
        return newTaskDisplayArea;
    }

    /** Creates a {@link Task} and adds it to the specified {@link DisplayContent}. */
    Task createTaskStackOnDisplay(DisplayContent dc) {
        return createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
    }

    Task createTaskStackOnDisplay(int windowingMode, int activityType, DisplayContent dc) {
        return new StackBuilder(dc.mWmService.mRoot)
                .setDisplay(dc)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setCreateActivity(false)
                .setIntent(new Intent())
                .build();
    }

    Task createTaskStackOnTaskDisplayArea(int windowingMode, int activityType,
            TaskDisplayArea tda) {
        return new StackBuilder(tda.mWmService.mRoot)
                .setTaskDisplayArea(tda)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setCreateActivity(false)
                .setIntent(new Intent())
                .build();
    }

    /** Creates a {@link Task} and adds it to the specified {@link Task}. */
    Task createTaskInStack(Task stack, int userId) {
        return WindowTestUtils.createTaskInStack(mWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplay(true /* supportIme */);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(boolean supportIme) {
        return createNewDisplay(mDisplayInfo, supportIme);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo info) {
        return createNewDisplay(info, true /* supportIme */);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(DisplayInfo info, boolean supportIme) {
        final DisplayContent display =
                new TestDisplayContent.Builder(mAtm, info).build();
        final DisplayContent dc = display.mDisplayContent;
        // this display can show IME.
        dc.mWmService.mDisplayWindowSettings.setShouldShowImeLocked(dc, supportIme);
        return dc;
    }

    /**
     * Creates a {@link DisplayContent} with given display state and adds it to the system.
     *
     * @param displayState For initializing the state of the display. See
     *                     {@link Display#getState()}.
     */
    DisplayContent createNewDisplay(int displayState) {
        // Leverage main display info & initialize it with display state for given displayId.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.state = displayState;
        return createNewDisplay(displayInfo, true /* supportIme */);
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        return new WindowTestUtils.TestWindowState(mWm, mMockSession, mIWindow, attrs, token);
    }

    /** Creates a {@link DisplayContent} as parts of simulate display info for test. */
    DisplayContent createMockSimulatedDisplay() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.ownerUid = SYSTEM_UID;
        return createNewDisplay(displayInfo, false /* supportIme */);
    }

    IDisplayWindowInsetsController createDisplayWindowInsetsController() {
        return new IDisplayWindowInsetsController.Stub() {

            @Override
            public void insetsChanged(InsetsState insetsState) throws RemoteException {
            }

            @Override
            public void insetsControlChanged(InsetsState insetsState,
                    InsetsSourceControl[] insetsSourceControls) throws RemoteException {
            }

            @Override
            public void showInsets(int i, boolean b) throws RemoteException {
            }

            @Override
            public void hideInsets(int i, boolean b) throws RemoteException {
            }

            @Override
            public void topFocusedWindowChanged(String packageName) {
            }
        };
    }

    /**
     * Avoids rotating screen disturbed by some conditions. It is usually used for the default
     * display that is not the instance of {@link TestDisplayContent} (it bypasses the conditions).
     *
     * @see DisplayRotation#updateRotationUnchecked
     */
    void unblockDisplayRotation(DisplayContent dc) {
        mWm.stopFreezingDisplayLocked();
        // The rotation animation won't actually play, it needs to be cleared manually.
        dc.setRotationAnimation(null);
    }

    // The window definition for UseTestDisplay#addWindows. The test can declare to add only
    // necessary windows, that avoids adding unnecessary overhead of unused windows.
    static final int W_NOTIFICATION_SHADE = TYPE_NOTIFICATION_SHADE;
    static final int W_STATUS_BAR = TYPE_STATUS_BAR;
    static final int W_NAVIGATION_BAR = TYPE_NAVIGATION_BAR;
    static final int W_INPUT_METHOD_DIALOG = TYPE_INPUT_METHOD_DIALOG;
    static final int W_INPUT_METHOD = TYPE_INPUT_METHOD;
    static final int W_DOCK_DIVIDER = TYPE_DOCK_DIVIDER;
    static final int W_ABOVE_ACTIVITY = TYPE_APPLICATION_ATTACHED_DIALOG;
    static final int W_ACTIVITY = TYPE_BASE_APPLICATION;
    static final int W_BELOW_ACTIVITY = TYPE_APPLICATION_MEDIA_OVERLAY;
    static final int W_WALLPAPER = TYPE_WALLPAPER;

    /** The common window types supported by {@link UseTestDisplay}. */
    @Retention(RetentionPolicy.RUNTIME)
    @IntDef(value = {
            W_NOTIFICATION_SHADE,
            W_STATUS_BAR,
            W_NAVIGATION_BAR,
            W_INPUT_METHOD_DIALOG,
            W_INPUT_METHOD,
            W_DOCK_DIVIDER,
            W_ABOVE_ACTIVITY,
            W_ACTIVITY,
            W_BELOW_ACTIVITY,
            W_WALLPAPER,
    })
    @interface CommonTypes {
    }

    /**
     * The annotation for class and method (higher priority) to create a non-default display that
     * will be assigned to {@link #mDisplayContent}. It is used if the test needs
     * <ul>
     * <li>Pure empty display.</li>
     * <li>Configured common windows.</li>
     * <li>Independent and customizable orientation.</li>
     * <li>Cross display operation.</li>
     * </ul>
     *
     * @see TestDisplayContent
     * @see #createTestDisplay
     **/
    @Target({ ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @interface UseTestDisplay {
        boolean addAllCommonWindows() default false;
        @CommonTypes int[] addWindows() default {};
    }

    /** Creates and adds a {@link TestDisplayContent} to supervisor at the given position. */
    TestDisplayContent addNewDisplayContentAt(int position) {
        return new TestDisplayContent.Builder(mAtm, 1000, 1500).setPosition(position).build();
    }

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    public void removeGlobalMinSizeRestriction() {
        mAtm.mRootWindowContainer.mDefaultMinSizeOfResizeableTaskDp = 1;
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private String mTargetActivity;
        private Task mTask;
        private String mProcessName = "name";
        private String mAffinity;
        private int mUid = 12345;
        private boolean mCreateTask;
        private Task mStack;
        private int mActivityFlags;
        private int mLaunchMode;
        private int mResizeMode = RESIZE_MODE_RESIZEABLE;
        private float mMaxAspectRatio;
        private int mScreenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        private boolean mLaunchTaskBehind;
        private int mConfigChanges;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;
        private WindowProcessController mWpc;
        private Bundle mIntentExtras;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTargetActivity(String targetActivity) {
            mTargetActivity = targetActivity;
            return this;
        }

        ActivityBuilder setIntentExtras(Bundle extras) {
            mIntentExtras = extras;
            return this;
        }

        static ComponentName getDefaultComponent() {
            return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                    DEFAULT_COMPONENT_PACKAGE_NAME);
        }

        ActivityBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        ActivityBuilder setActivityFlags(int flags) {
            mActivityFlags = flags;
            return this;
        }

        ActivityBuilder setLaunchMode(int launchMode) {
            mLaunchMode = launchMode;
            return this;
        }

        ActivityBuilder setStack(Task stack) {
            mStack = stack;
            return this;
        }

        ActivityBuilder setCreateTask(boolean createTask) {
            mCreateTask = createTask;
            return this;
        }

        ActivityBuilder setProcessName(String name) {
            mProcessName = name;
            return this;
        }

        ActivityBuilder setUid(int uid) {
            mUid = uid;
            return this;
        }

        ActivityBuilder setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
            return this;
        }

        ActivityBuilder setMaxAspectRatio(float maxAspectRatio) {
            mMaxAspectRatio = maxAspectRatio;
            return this;
        }

        ActivityBuilder setScreenOrientation(int screenOrientation) {
            mScreenOrientation = screenOrientation;
            return this;
        }

        ActivityBuilder setLaunchTaskBehind(boolean launchTaskBehind) {
            mLaunchTaskBehind = launchTaskBehind;
            return this;
        }

        ActivityBuilder setConfigChanges(int configChanges) {
            mConfigChanges = configChanges;
            return this;
        }

        ActivityBuilder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        ActivityBuilder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
            return this;
        }

        ActivityBuilder setUseProcess(WindowProcessController wpc) {
            mWpc = wpc;
            return this;
        }

        ActivityBuilder setAffinity(String affinity) {
            mAffinity = affinity;
            return this;
        }

        ActivityRecord build() {
            SystemServicesTestRule.checkHoldsLock(mService.mGlobalLock);
            try {
                mService.deferWindowLayout();
                return buildInner();
            } finally {
                mService.continueWindowLayout();
            }
        }

        ActivityRecord buildInner() {
            if (mComponent == null) {
                final int id = sCurrentActivityId++;
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME + id);
            }

            if (mCreateTask) {
                mTask = new TaskBuilder(mService.mStackSupervisor)
                        .setComponent(mComponent)
                        .setStack(mStack).build();
            } else if (mTask == null && mStack != null && DisplayContent.alwaysCreateStack(
                    mStack.getWindowingMode(), mStack.getActivityType())) {
                // The stack can be the task root.
                mTask = mStack;
            }

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            if (mIntentExtras != null) {
                intent.putExtras(mIntentExtras);
            }
            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
            aInfo.applicationInfo.packageName = mComponent.getPackageName();
            aInfo.applicationInfo.uid = mUid;
            aInfo.processName = mProcessName;
            aInfo.packageName = mComponent.getPackageName();
            aInfo.name = mComponent.getClassName();
            if (mTargetActivity != null) {
                aInfo.targetActivity = mTargetActivity;
            }
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;
            aInfo.resizeMode = mResizeMode;
            aInfo.maxAspectRatio = mMaxAspectRatio;
            aInfo.screenOrientation = mScreenOrientation;
            aInfo.configChanges |= mConfigChanges;
            aInfo.taskAffinity = mAffinity;

            ActivityOptions options = null;
            if (mLaunchTaskBehind) {
                options = ActivityOptions.makeTaskLaunchBehind();
            }

            final ActivityRecord activity = new ActivityRecord(mService, null /* caller */,
                    mLaunchedFromPid /* launchedFromPid */, mLaunchedFromUid /* launchedFromUid */,
                    null, null, intent, null, aInfo /*aInfo*/, new Configuration(),
                    null /* resultTo */, null /* resultWho */, 0 /* reqCode */,
                    false /*componentSpecified*/, false /* rootVoiceInteraction */,
                    mService.mStackSupervisor, options, null /* sourceRecord */);
            spyOn(activity);
            if (mTask != null) {
                // fullscreen value is normally read from resources in ctor, so for testing we need
                // to set it somewhere else since we can't mock resources.
                doReturn(true).when(activity).occludesParent();
                doReturn(true).when(activity).fillsParent();
                mTask.addChild(activity);
                // Make visible by default...
                activity.setVisible(true);
            }

            final WindowProcessController wpc;
            if (mWpc != null) {
                wpc = mWpc;
            } else {
                wpc = new WindowProcessController(mService,
                        aInfo.applicationInfo, mProcessName, mUid,
                        UserHandle.getUserId(12345), mock(Object.class),
                        mock(WindowProcessListener.class));
                wpc.setThread(mock(IApplicationThread.class));
            }
            wpc.setThread(mock(IApplicationThread.class));
            activity.setProcess(wpc);
            doReturn(wpc).when(mService).getProcessController(
                    activity.processName, activity.info.applicationInfo.uid);

            // Resume top activities to make sure all other signals in the system are connected.
            mService.mRootWindowContainer.resumeFocusedStacksTopActivities();
            return activity;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        private final ActivityStackSupervisor mSupervisor;

        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        // Task id 0 is reserved in ARC for the home app.
        private int mTaskId = SystemServicesTestRule.sNextTaskId++;
        private int mUserId = 0;
        private IVoiceInteractionSession mVoiceSession;
        private boolean mCreateStack = true;

        private Task mStack;
        private TaskDisplayArea mTaskDisplayArea;

        TaskBuilder(ActivityStackSupervisor supervisor) {
            mSupervisor = supervisor;
        }

        TaskBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        TaskBuilder setPackage(String packageName) {
            mPackage = packageName;
            return this;
        }

        /**
         * Set to {@code true} by default, set to {@code false} to prevent the task from
         * automatically creating a parent stack.
         */
        TaskBuilder setCreateStack(boolean createStack) {
            mCreateStack = createStack;
            return this;
        }

        TaskBuilder setVoiceSession(IVoiceInteractionSession session) {
            mVoiceSession = session;
            return this;
        }

        TaskBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        TaskBuilder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        TaskBuilder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        TaskBuilder setStack(Task stack) {
            mStack = stack;
            return this;
        }

        TaskBuilder setDisplay(DisplayContent display) {
            mTaskDisplayArea = display.getDefaultTaskDisplayArea();
            return this;
        }

        Task build() {
            SystemServicesTestRule.checkHoldsLock(mSupervisor.mService.mGlobalLock);

            if (mStack == null && mCreateStack) {
                TaskDisplayArea displayArea = mTaskDisplayArea != null ? mTaskDisplayArea
                        : mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
                mStack = displayArea.createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
                spyOn(mStack);
            }

            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = mPackage;

            Intent intent = new Intent();
            if (mComponent == null) {
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME);
            }

            intent.setComponent(mComponent);
            intent.setFlags(mFlags);

            final Task task = new Task(mSupervisor.mService, mTaskId, aInfo,
                    intent /*intent*/, mVoiceSession, null /*_voiceInteractor*/,
                    null /*taskDescription*/, mStack);
            spyOn(task);
            task.mUserId = mUserId;

            if (mStack != null) {
                mStack.moveToFront("test");
                mStack.addChild(task, true, true);
            }

            return task;
        }
    }

    static class StackBuilder {
        private final RootWindowContainer mRootWindowContainer;
        private DisplayContent mDisplay;
        private TaskDisplayArea mTaskDisplayArea;
        private int mStackId = -1;
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private boolean mOnTop = true;
        private boolean mCreateActivity = true;
        private ActivityInfo mInfo;
        private Intent mIntent;

        StackBuilder(RootWindowContainer root) {
            mRootWindowContainer = root;
            mDisplay = mRootWindowContainer.getDefaultDisplay();
            mTaskDisplayArea = mDisplay.getDefaultTaskDisplayArea();
        }

        StackBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        StackBuilder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        StackBuilder setStackId(int stackId) {
            mStackId = stackId;
            return this;
        }

        /**
         * Set the parent {@link DisplayContent} and use the default task display area. Overrides
         * the task display area, if was set before.
         */
        StackBuilder setDisplay(DisplayContent display) {
            mDisplay = display;
            mTaskDisplayArea = mDisplay.getDefaultTaskDisplayArea();
            return this;
        }

        /** Set the parent {@link TaskDisplayArea}. Overrides the display, if was set before. */
        StackBuilder setTaskDisplayArea(TaskDisplayArea taskDisplayArea) {
            mTaskDisplayArea = taskDisplayArea;
            mDisplay = mTaskDisplayArea.mDisplayContent;
            return this;
        }

        StackBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        StackBuilder setCreateActivity(boolean createActivity) {
            mCreateActivity = createActivity;
            return this;
        }

        StackBuilder setActivityInfo(ActivityInfo info) {
            mInfo = info;
            return this;
        }

        StackBuilder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        Task build() {
            SystemServicesTestRule.checkHoldsLock(mRootWindowContainer.mWmService.mGlobalLock);

            final int stackId = mStackId >= 0 ? mStackId : mTaskDisplayArea.getNextStackId();
            final Task stack = mTaskDisplayArea.createStackUnchecked(
                    mWindowingMode, mActivityType, stackId, mOnTop, mInfo, mIntent,
                    false /* createdByOrganizer */);
            final ActivityStackSupervisor supervisor = mRootWindowContainer.mStackSupervisor;

            if (mCreateActivity) {
                new ActivityBuilder(supervisor.mService)
                        .setCreateTask(true)
                        .setStack(stack)
                        .build();
                if (mOnTop) {
                    // We move the task to front again in order to regain focus after activity
                    // added to the stack. Or {@link DisplayContent#mPreferredTopFocusableStack}
                    // could be other stacks (e.g. home stack).
                    stack.moveToFront("createActivityStack");
                } else {
                    stack.moveToBack("createActivityStack", null);
                }
            }
            spyOn(stack);

            doNothing().when(stack).startActivityLocked(
                    any(), any(), anyBoolean(), anyBoolean(), any());

            return stack;
        }

    }

    static class TestSplitOrganizer extends ITaskOrganizer.Stub {
        final ActivityTaskManagerService mService;
        Task mPrimary;
        Task mSecondary;
        boolean mInSplit = false;
        // moves everything to secondary. Most tests expect this since sysui usually does it.
        boolean mMoveToSecondaryOnEnter = true;
        int mDisplayId;
        TestSplitOrganizer(ActivityTaskManagerService service, int displayId) {
            mService = service;
            mDisplayId = displayId;
            mService.mTaskOrganizerController.registerTaskOrganizer(this,
                    WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
            mService.mTaskOrganizerController.registerTaskOrganizer(this,
                    WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
            WindowContainerToken primary = mService.mTaskOrganizerController.createRootTask(
                    displayId, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY).token;
            mPrimary = WindowContainer.fromBinder(primary.asBinder()).asTask();
            WindowContainerToken secondary = mService.mTaskOrganizerController.createRootTask(
                    displayId, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY).token;
            mSecondary = WindowContainer.fromBinder(secondary.asBinder()).asTask();
        }
        TestSplitOrganizer(ActivityTaskManagerService service) {
            this(service,
                    service.mStackSupervisor.mRootWindowContainer.getDefaultDisplay().mDisplayId);
        }
        public void setMoveToSecondaryOnEnter(boolean move) {
            mMoveToSecondaryOnEnter = move;
        }
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        }
        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        }
        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
            if (mInSplit) {
                return;
            }
            if (info.topActivityType == ACTIVITY_TYPE_UNDEFINED) {
                // Not populated
                return;
            }
            if (info.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                return;
            }
            mInSplit = true;
            if (!mMoveToSecondaryOnEnter) {
                return;
            }
            mService.mTaskOrganizerController.setLaunchRoot(mDisplayId,
                    mSecondary.mRemoteToken.toWindowContainerToken());
            DisplayContent dc = mService.mRootWindowContainer.getDisplayContent(mDisplayId);
            dc.forAllTaskDisplayAreas(taskDisplayArea -> {
                for (int sNdx = taskDisplayArea.getStackCount() - 1; sNdx >= 0; --sNdx) {
                    final Task stack = taskDisplayArea.getStackAt(sNdx);
                    if (!WindowConfiguration.isSplitScreenWindowingMode(stack.getWindowingMode())) {
                        stack.reparent(mSecondary, POSITION_BOTTOM);
                    }
                }
            });
        }
        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        }
    }
}
