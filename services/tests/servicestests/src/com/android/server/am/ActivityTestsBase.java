/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

import org.mockito.invocation.InvocationOnMock;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import com.android.server.AttributeCache;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.StackWindowController;

import com.android.server.wm.TaskWindowContainerController;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowTestUtils;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

/**
 * A base class to handle common operations in activity related unit tests.
 */
public class ActivityTestsBase {
    private final Context mContext = InstrumentationRegistry.getContext();
    private HandlerThread mHandlerThread;

    // Grabbing an instance of {@link WindowManagerService} creates it if not present so this must
    // be called at before any tests.
    private final WindowManagerService mWms = WindowTestUtils.getWindowManagerService(mContext);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandlerThread = new HandlerThread("ActivityTestsBaseThread");
        mHandlerThread.start();
    }

    @After
    public void tearDown() {
        mHandlerThread.quitSafely();
    }

    protected ActivityManagerService createActivityManagerService() {
        final ActivityManagerService service = new TestActivityManagerService(mContext);
        service.mWindowManager = WindowTestUtils.getMockWindowManagerService();
        return service;
    }

    protected static ActivityStack createActivityStack(ActivityManagerService service,
            int stackId, int displayId, boolean onTop) {
        if (service.mStackSupervisor instanceof TestActivityStackSupervisor) {
            return ((TestActivityStackSupervisor) service.mStackSupervisor)
                    .createTestStack(stackId, onTop);
        }

        return null;
    }

    protected static ActivityRecord createActivity(ActivityManagerService service,
            ComponentName component, TaskRecord task) {
        return createActivity(service, component, task, 0 /* userId */);
    }

    protected static ActivityRecord createActivity(ActivityManagerService service,
            ComponentName component, TaskRecord task, int uid) {
        Intent intent = new Intent();
        intent.setComponent(component);
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();
        aInfo.applicationInfo.uid = uid;
        AttributeCache.init(service.mContext);
        final ActivityRecord activity = new ActivityRecord(service, null /* caller */,
                0 /* launchedFromPid */, 0, null, intent, null,
                aInfo /*aInfo*/, new Configuration(), null /* resultTo */, null /* resultWho */,
                0 /* reqCode */, false /*componentSpecified*/, false /* rootVoiceInteraction */,
                service.mStackSupervisor, null /* options */, null /* sourceRecord */);
        activity.mWindowContainerController = mock(AppWindowContainerController.class);

        if (task != null) {
            task.addActivityToTop(activity);
        }

        return activity;
    }

    protected static TaskRecord createTask(ActivityManagerService service,
            ComponentName component, int stackId) {
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();

        Intent intent = new Intent();
        intent.setComponent(component);

        final TaskRecord task = new TaskRecord(service, 0, aInfo, intent /*intent*/,
                null /*_taskDescription*/, new ActivityManager.TaskThumbnailInfo());
        final ActivityStack stack = service.mStackSupervisor.getStack(stackId,
                true /*createStaticStackIfNeeded*/, true /*onTop*/);
        service.mStackSupervisor.setFocusStackUnchecked("test", stack);
        stack.addTask(task, true, "creating test task");
        task.setStack(stack);
        task.setWindowContainerController(mock(TaskWindowContainerController.class));

        return task;
    }


    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    protected static class TestActivityManagerService extends ActivityManagerService {
        public TestActivityManagerService(Context context) {
            super(context);
            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mWindowManager = WindowTestUtils.getWindowManagerService(context);
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            return new TestActivityStackSupervisor(this, mHandlerThread.getLooper());
        }

        @Override
        void updateUsageStats(ActivityRecord component, boolean resumed) {
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected static class TestActivityStackSupervisor extends ActivityStackSupervisor {
        private final ActivityDisplay mDisplay;
        private boolean mLastResizeable;

        public TestActivityStackSupervisor(ActivityManagerService service, Looper looper) {
            super(service, looper);
            mWindowManager = prepareMockWindowManager();
            mDisplay = new ActivityDisplay();
        }

        // TODO: Use Mockito spy instead. Currently not possible due to TestActivityStackSupervisor
        // access to ActivityDisplay
        @Override
        boolean canPlaceEntityOnDisplay(int displayId, boolean resizeable, int callingPid,
                int callingUid, ActivityInfo activityInfo) {
            mLastResizeable = resizeable;
            return super.canPlaceEntityOnDisplay(displayId, resizeable, callingPid, callingUid,
                    activityInfo);
        }

        // TODO: remove and use Mockito verify once {@link #canPlaceEntityOnDisplay} override is
        // removed.
        public boolean getLastResizeableFromCanPlaceEntityOnDisplay() {
            return mLastResizeable;
        }

        // No home stack is set.
        @Override
        void moveHomeStackToFront(String reason) {
        }

        @Override
        boolean moveHomeStackTaskToTop(String reason) {
            return true;
        }

        // Invoked during {@link ActivityStack} creation.
        @Override
        void updateUIDsPresentOnDisplay() {
        }

        // Just return the current front task.
        @Override
        ActivityStack getNextFocusableStackLocked(ActivityStack currentFocus) {
            return mFocusedStack;
        }

        // Called when moving activity to pinned stack.
        @Override
        void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
                boolean preserveWindows) {
        }

        <T extends ActivityStack> T createTestStack(int stackId, boolean onTop) {
            return (T) createStack(stackId, mDisplay, onTop);
        }

        @Override
        ActivityStack createStack(int stackId, ActivityDisplay display, boolean onTop) {
            final RecentTasks recents =
                    new RecentTasks(mService, mService.mStackSupervisor);
            if (stackId == PINNED_STACK_ID) {
                return new PinnedActivityStack(display, stackId, this, recents, onTop) {
                    @Override
                    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
                        return new Rect(50, 50, 100, 100);
                    }
                };
            } else {
                return new TestActivityStack(display, stackId, this, recents, onTop);
            }
        }

        @Override
        protected <T extends ActivityStack> T getStack(int stackId,
                boolean createStaticStackIfNeeded, boolean createOnTop) {
            final T stack = super.getStack(stackId, createStaticStackIfNeeded, createOnTop);

            if (stack != null || !createStaticStackIfNeeded) {
                return stack;
            }

            return createTestStack(stackId, createOnTop);
        }

        // Always keep things awake
        @Override
        boolean hasAwakeDisplay() {
            return true;
        }
    }

    private static WindowManagerService prepareMockWindowManager() {
        final WindowManagerService service = mock(WindowManagerService.class);

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(service).inSurfaceTransaction(any());

        return service;
    }

    protected interface ActivityStackReporter {
        int onActivityRemovedFromStackInvocationCount();
    }

    /**
     * Override of {@link ActivityStack} that tracks test metrics, such as the number of times a
     * method is called. Note that its functionality depends on the implementations of the
     * construction arguments.
     */
    protected static class TestActivityStack<T extends StackWindowController>
            extends ActivityStack<T> implements ActivityStackReporter {
        private int mOnActivityRemovedFromStackCount = 0;
        private T mContainerController;
        TestActivityStack(ActivityStackSupervisor.ActivityDisplay display, int stackId,
                ActivityStackSupervisor supervisor, RecentTasks recentTasks, boolean onTop) {
            super(display, stackId, supervisor, recentTasks, onTop);
        }

        @Override
        void onActivityRemovedFromStack(ActivityRecord r) {
            mOnActivityRemovedFromStackCount++;
            super.onActivityRemovedFromStack(r);
        }

        // Returns the number of times {@link #onActivityRemovedFromStack} has been called
        @Override
        public int onActivityRemovedFromStackInvocationCount() {
            return mOnActivityRemovedFromStackCount;
        }

        @Override
        protected T createStackWindowController(int displayId, boolean onTop,
                Rect outBounds) {
            mContainerController = (T) WindowTestUtils.createMockStackWindowContainerController();
            return mContainerController;
        }

        @Override
        T getWindowContainerController() {
            return mContainerController;
        }
    }


    protected static class ActivityStackBuilder {
        private boolean mOnTop = true;
        private int mStackId = 0;
        private int mDisplayId = 1;

        private final ActivityManagerService mService;

        public ActivityStackBuilder(ActivityManagerService ams) {
            mService = ams;
        }

        public ActivityStackBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        public ActivityStackBuilder setStackId(int id) {
            mStackId = id;
            return this;
        }

        public ActivityStackBuilder setDisplayId(int id) {
            mDisplayId = id;
            return this;
        }

        public ActivityStack build() {
            return createActivityStack(mService, mStackId, mDisplayId, mOnTop);
        }
    }
}
