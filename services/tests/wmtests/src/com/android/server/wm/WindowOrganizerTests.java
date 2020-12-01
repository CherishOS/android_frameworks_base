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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowContainer.SYNC_STATE_READY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.Rational;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.IWindowContainerTransactionCallback;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Test class for {@link ITaskOrganizer} and {@link android.window.ITaskOrganizerController}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowOrganizerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowOrganizerTests extends WindowTestsBase {

    private ITaskOrganizer createMockOrganizer() {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());
        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer(ArrayList<TaskAppearedInfo> existingTasks) {
        final ITaskOrganizer organizer = createMockOrganizer();
        ParceledListSlice<TaskAppearedInfo> tasks =
                mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(organizer);
        if (existingTasks != null) {
            existingTasks.addAll(tasks.getList());
        }
        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer() {
        return registerMockOrganizer(null);
    }

    Task createTask(Task stack, boolean fakeDraw) {
        final Task task = createTaskInStack(stack, 0);

        if (fakeDraw) {
            task.setHasBeenVisible(true);
        }
        return task;
    }

    Task createTask(Task stack) {
        // Fake draw notifications for most of our tests.
        return createTask(stack, true);
    }

    Task createStack() {
        return createTaskStackOnDisplay(mDisplayContent);
    }

    @Before
    public void setUp() {
        // We defer callbacks since we need to adjust task surface visibility, but for these tests,
        // just run the callbacks synchronously
        mWm.mAtmService.mTaskOrganizerController.setDeferTaskOrgCallbacksConsumer((r) -> r.run());
    }

    @Test
    public void testAppearVanish() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack);

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        stack.removeImmediately();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testAppearWaitsForVisibility() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack, false);

        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        stack.setHasBeenVisible(true);
        assertTrue(stack.getHasBeenVisible());

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        stack.removeImmediately();
        verify(organizer).onTaskVanished(any());
    }

    @Test
    public void testNoVanishedIfNoAppear() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack, false /* hasBeenVisible */);

        // In this test we skip making the Task visible, and verify
        // that even though a TaskOrganizer is set remove doesn't emit
        // a vanish callback, because we never emitted appear.
        stack.setTaskOrganizer(organizer);
        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        stack.removeImmediately();
        verify(organizer, never()).onTaskVanished(any());
    }

    @Test
    public void testTaskNoDraw() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack, false /* fakeDraw */);

        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(stack.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        assertTaskVanished(organizer, false /* expectVanished */, stack);
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack);

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(stack.isOrganized());

        stack.setTaskOrganizer(null);
        verify(organizer).onTaskVanished(any());
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testUnregisterOrganizer() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack);

        verify(organizer).onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTrue(stack.isOrganized());

        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer);
        assertTaskVanished(organizer, true /* expectVanished */, stack);
        assertFalse(stack.isOrganized());
    }

    @Test
    public void testUnregisterOrganizerReturnsRegistrationToPrevious() throws RemoteException {
        final Task stack = createStack();
        final Task task = createTask(stack);
        final Task stack2 = createStack();
        final Task task2 = createTask(stack2);
        final Task stack3 = createStack();
        final Task task3 = createTask(stack3);
        final ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);

        // verify that tasks are returned and taskAppeared is not called
        assertContainsTasks(existingTasks, stack, stack2, stack3);
        verify(organizer, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer, times(0)).onTaskVanished(any());
        assertTrue(stack.isOrganized());

        // Now we replace the registration and verify the new organizer receives existing tasks
        final ArrayList<TaskAppearedInfo> existingTasks2 = new ArrayList<>();
        final ITaskOrganizer organizer2 = registerMockOrganizer(existingTasks2);
        assertContainsTasks(existingTasks2, stack, stack2, stack3);
        verify(organizer2, times(0)).onTaskAppeared(any(RunningTaskInfo.class),
                any(SurfaceControl.class));
        verify(organizer2, times(0)).onTaskVanished(any());
        // Removed tasks from the original organizer
        assertTaskVanished(organizer, true /* expectVanished */, stack, stack2, stack3);
        assertTrue(stack2.isOrganized());

        // Now we unregister the second one, the first one should automatically be reregistered
        // so we verify that it's now seeing changes.
        mWm.mAtmService.mTaskOrganizerController.unregisterTaskOrganizer(organizer2);
        verify(organizer, times(3))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
        assertTaskVanished(organizer2, true /* expectVanished */, stack, stack2, stack3);
    }

    @Test
    public void testRegisterTaskOrganizerWithExistingTasks() throws RemoteException {
        final Task stack = createStack();
        final Task task = createTask(stack);
        final Task stack2 = createStack();
        final Task task2 = createTask(stack2);
        ArrayList<TaskAppearedInfo> existingTasks = new ArrayList<>();
        final ITaskOrganizer organizer = registerMockOrganizer(existingTasks);
        assertContainsTasks(existingTasks, stack, stack2);

        // Verify we don't get onTaskAppeared if we are returned the tasks
        verify(organizer, never())
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final Task stack = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        testTransaction(task);
    }

    @Test
    public void testStackTransaction() {
        removeGlobalMinSizeRestriction();
        final Task stack = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        RootTaskInfo info =
                mWm.mAtmService.getRootTaskInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        assertEquals(stack.mRemoteToken.toWindowContainerToken(), info.token);
        testTransaction(stack);
    }

    @Test
    public void testDisplayAreaTransaction() {
        removeGlobalMinSizeRestriction();
        final DisplayArea displayArea = new DisplayArea<>(mWm, ABOVE_TASKS, "DisplayArea");
        testTransaction(displayArea);
    }

    private void testTransaction(WindowContainer wc) {
        WindowContainerTransaction t = new WindowContainerTransaction();
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(wc.mRemoteToken.toWindowContainerToken(), new Rect(10, 10, 100, 100));
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(newBounds, wc.getBounds());
    }

    @Test
    public void testSetWindowingMode() {
        final Task stack = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        testSetWindowingMode(stack);

        final DisplayArea displayArea = new DisplayArea<>(mWm, ABOVE_TASKS, "DisplayArea");
        displayArea.setWindowingMode(WINDOWING_MODE_FREEFORM);
        testSetWindowingMode(displayArea);
    }

    private void testSetWindowingMode(WindowContainer wc) {
        final WindowContainerTransaction t = new WindowContainerTransaction();
        t.setWindowingMode(wc.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(WINDOWING_MODE_FULLSCREEN, wc.getWindowingMode());
    }

    @Test
    public void testSetActivityWindowingMode() {
        final ActivityRecord record = makePipableActivity();
        final Task stack = record.getStack();
        final WindowContainerTransaction t = new WindowContainerTransaction();

        t.setWindowingMode(stack.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_PINNED);
        t.setActivityWindowingMode(
                stack.mRemoteToken.toWindowContainerToken(), WINDOWING_MODE_FULLSCREEN);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        assertEquals(WINDOWING_MODE_FULLSCREEN, record.getWindowingMode());
        assertEquals(WINDOWING_MODE_PINNED, stack.getWindowingMode());
    }

    @Test
    public void testContainerFocusableChanges() {
        removeGlobalMinSizeRestriction();
        final Task stack = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(task.isFocusable());
        t.setFocusable(stack.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(task.isFocusable());
        t.setFocusable(stack.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(task.isFocusable());
    }

    @Test
    public void testContainerHiddenChanges() {
        removeGlobalMinSizeRestriction();
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertTrue(stack.shouldBeVisible(null));
        t.setHidden(stack.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertFalse(stack.shouldBeVisible(null));
        t.setHidden(stack.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertTrue(stack.shouldBeVisible(null));
    }

    @Test
    public void testSetIgnoreOrientationRequest_taskDisplayArea() {
        removeGlobalMinSizeRestriction();
        final TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task stack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(stack).build();
        taskDisplayArea.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplayContent.setFocusedApp(activity);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        // TDA returns UNSET when ignoreOrientationRequest == true
        // DC is UNSPECIFIED when child returns UNSET
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSET);
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setIgnoreOrientationRequest(
                taskDisplayArea.mRemoteToken.toWindowContainerToken(),
                false /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // TDA returns app request orientation when ignoreOrientationRequest == false
        // DC uses the same as TDA returns when it is not UNSET.
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);

        t.setIgnoreOrientationRequest(
                taskDisplayArea.mRemoteToken.toWindowContainerToken(),
                true /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // TDA returns UNSET when ignoreOrientationRequest == true
        // DC is UNSPECIFIED when child returns UNSET
        assertThat(taskDisplayArea.getOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSET);
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Test
    public void testSetIgnoreOrientationRequest_displayContent() {
        removeGlobalMinSizeRestriction();
        final TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task stack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(stack).build();
        mDisplayContent.setFocusedApp(activity);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        // DC uses the orientation request from app
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);

        WindowContainerTransaction t = new WindowContainerTransaction();
        t.setIgnoreOrientationRequest(
                mDisplayContent.mRemoteToken.toWindowContainerToken(),
                true /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // DC returns UNSPECIFIED when ignoreOrientationRequest == true
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        t.setIgnoreOrientationRequest(
                mDisplayContent.mRemoteToken.toWindowContainerToken(),
                false /* ignoreOrientationRequest */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);

        // DC uses the orientation request from app after mIgnoreOrientationRequest is set to false
        assertThat(mDisplayContent.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Test
    public void testOverrideConfigSize() {
        removeGlobalMinSizeRestriction();
        final Task stack = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        final int origScreenWDp = task.getConfiguration().screenHeightDp;
        final int origScreenHDp = task.getConfiguration().screenHeightDp;
        t = new WindowContainerTransaction();
        // verify that setting config overrides on parent restricts children.
        t.setScreenSizeDp(stack.mRemoteToken
                .toWindowContainerToken(), origScreenWDp, origScreenHDp / 2);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(origScreenHDp / 2, task.getConfiguration().screenHeightDp);
        t = new WindowContainerTransaction();
        t.setScreenSizeDp(stack.mRemoteToken.toWindowContainerToken(), SCREEN_WIDTH_DP_UNDEFINED,
                SCREEN_HEIGHT_DP_UNDEFINED);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        assertEquals(origScreenHDp, task.getConfiguration().screenHeightDp);
    }

    @Test
    public void testCreateDeleteRootTasks() {
        DisplayContent dc = mWm.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY);

        Task task1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, null);
        RunningTaskInfo info1 = task1.getTaskInfo();
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                info1.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);

        Task task2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                dc, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);
        RunningTaskInfo info2 = task2.getTaskInfo();
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                info2.configuration.windowConfiguration.getWindowingMode());
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info2.topActivityType);

        List<Task> infos = getTasksCreatedByOrganizer(dc);
        assertEquals(2, infos.size());

        assertTrue(mWm.mAtmService.mTaskOrganizerController.deleteRootTask(info1.token));
        infos = getTasksCreatedByOrganizer(dc);
        assertEquals(1, infos.size());
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, infos.get(0).getWindowingMode());
    }

    @Test
    public void testTileAddRemoveChild() {
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void addStartingWindow(ActivityManager.RunningTaskInfo info, IBinder appToken) {

            }

            @Override
            public void removeStartingWindow(ActivityManager.RunningTaskInfo info) { }

            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) throws RemoteException {
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);
        Task task = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);
        RunningTaskInfo info1 = task.getTaskInfo();

        final Task stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(info1.configuration.windowConfiguration.getWindowingMode(),
                stack.getWindowingMode());

        // Info should reflect new membership
        List<Task> infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_STANDARD, info1.topActivityType);

        // Children inherit configuration
        Rect newSize = new Rect(10, 10, 300, 300);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        Configuration c = new Configuration(task1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(newSize);
        doNothing().when(stack).adjustForMinimalTaskDimensions(any(), any(), any());
        task1.onRequestedOverrideConfigurationChanged(c);
        assertEquals(newSize, stack.getBounds());

        wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertEquals(mDisplayContent.getWindowingMode(), stack.getWindowingMode());
        infos = getTasksCreatedByOrganizer(mDisplayContent);
        info1 = infos.get(0).getTaskInfo();
        assertEquals(ACTIVITY_TYPE_UNDEFINED, info1.topActivityType);
    }

    @UseTestDisplay
    @Test
    public void testTaskInfoCallback() {
        final ArrayList<RunningTaskInfo> lastReportedTiles = new ArrayList<>();
        final boolean[] called = {false};
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void addStartingWindow(ActivityManager.RunningTaskInfo info, IBinder appToken) {

            }

            @Override
            public void removeStartingWindow(ActivityManager.RunningTaskInfo info) { }

            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) throws RemoteException {
                lastReportedTiles.add(info);
                called[0] = true;
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);
        Task task = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);
        RunningTaskInfo info1 = task.getTaskInfo();
        lastReportedTiles.clear();
        called[0] = false;

        final Task stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        Task task1 = WindowContainer.fromBinder(info1.token.asBinder()).asTask();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        final Task stack2 = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mDisplayContent);
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_HOME, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        task1.positionChildAt(POSITION_TOP, stack, false /* includingParents */);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_STANDARD, lastReportedTiles.get(0).topActivityType);

        lastReportedTiles.clear();
        called[0] = false;
        wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(called[0]);
        assertEquals(ACTIVITY_TYPE_UNDEFINED, lastReportedTiles.get(0).topActivityType);
    }

    @UseTestDisplay
    @Test
    public void testHierarchyTransaction() {
        final ArrayMap<IBinder, RunningTaskInfo> lastReportedTiles = new ArrayMap<>();
        ITaskOrganizer listener = new ITaskOrganizer.Stub() {
            @Override
            public void addStartingWindow(ActivityManager.RunningTaskInfo info, IBinder appToken) {

            }

            @Override
            public void removeStartingWindow(ActivityManager.RunningTaskInfo info) { }

            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) { }

            @Override
            public void onTaskVanished(RunningTaskInfo container) { }

            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                lastReportedTiles.put(info.token.asBinder(), info);
            }

            @Override
            public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            }
        };
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(listener);

        Task task1 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, null);
        RunningTaskInfo info1 = task1.getTaskInfo();
        Task task2 = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);
        RunningTaskInfo info2 = task2.getTaskInfo();

        final int initialRootTaskCount = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */).size();

        final Task stack = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task stack2 = createTaskStackOnDisplay(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, mDisplayContent);

        // Check getRootTasks works
        List<RunningTaskInfo> roots = mWm.mAtmService.mTaskOrganizerController.getRootTasks(
                mDisplayContent.mDisplayId, null /* activityTypes */);
        assertEquals(initialRootTaskCount + 2, roots.size());

        lastReportedTiles.clear();
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(stack.mRemoteToken.toWindowContainerToken(), info1.token, true /* onTop */);
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info2.token, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertFalse(lastReportedTiles.isEmpty());
        assertEquals(ACTIVITY_TYPE_STANDARD,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), info1.token, false /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertFalse(lastReportedTiles.isEmpty());
        // Standard should still be on top of tile 1, so no change there
        assertFalse(lastReportedTiles.containsKey(info1.token.asBinder()));
        // But tile 2 has no children, so should become undefined
        assertEquals(ACTIVITY_TYPE_UNDEFINED,
                lastReportedTiles.get(info2.token.asBinder()).topActivityType);

        // Check the getChildren call
        List<RunningTaskInfo> children =
                mWm.mAtmService.mTaskOrganizerController.getChildTasks(info1.token,
                        null /* activityTypes */);
        assertEquals(2, children.size());
        children = mWm.mAtmService.mTaskOrganizerController.getChildTasks(info2.token,
                null /* activityTypes */);
        assertEquals(0, children.size());

        // Check that getRootTasks doesn't include children of tiles
        roots = mWm.mAtmService.mTaskOrganizerController.getRootTasks(mDisplayContent.mDisplayId,
                null /* activityTypes */);
        assertEquals(initialRootTaskCount, roots.size());

        lastReportedTiles.clear();
        wct = new WindowContainerTransaction();
        wct.reorder(stack2.mRemoteToken.toWindowContainerToken(), true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        // Home should now be on top. No change occurs in second tile, so not reported
        assertEquals(1, lastReportedTiles.size());
        assertEquals(ACTIVITY_TYPE_HOME,
                lastReportedTiles.get(info1.token.asBinder()).topActivityType);

        // This just needs to not crash (ie. it should be possible to reparent to display twice)
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        wct = new WindowContainerTransaction();
        wct.reparent(stack2.mRemoteToken.toWindowContainerToken(), null, true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
    }

    private List<Task> getTasksCreatedByOrganizer(DisplayContent dc) {
        ArrayList<Task> out = new ArrayList<>();
        dc.forAllTaskDisplayAreas(taskDisplayArea -> {
            for (int sNdx = taskDisplayArea.getRootTaskCount() - 1; sNdx >= 0; --sNdx) {
                final Task t = taskDisplayArea.getRootTaskAt(sNdx);
                if (t.mCreatedByOrganizer) out.add(t);
            }
        });
        return out;
    }

    @Test
    public void testBLASTCallbackWithActivityChildren() {
        final Task stackController1 = createStack();
        final Task task = createTask(stackController1);
        final WindowState w = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window");

        w.mActivityRecord.mVisibleRequested = true;
        w.mActivityRecord.setVisible(true);

        BLASTSyncEngine bse = new BLASTSyncEngine(mWm);

        BLASTSyncEngine.TransactionReadyListener transactionListener =
                mock(BLASTSyncEngine.TransactionReadyListener.class);

        int id = bse.startSyncSet(transactionListener);
        bse.addToSyncSet(id, task);
        bse.setReady(id);
        bse.onSurfacePlacement();

        // Even though w is invisible (and thus activity isn't waiting on it), activity will
        // continue to wait until it has at-least 1 visible window.
        // Since we have a child window we still shouldn't be done.
        verify(transactionListener, never()).onTransactionReady(anyInt(), any());

        makeWindowVisible(w);
        bse.onSurfacePlacement();
        w.immediatelyNotifyBlastSync();
        bse.onSurfacePlacement();

        verify(transactionListener).onTransactionReady(anyInt(), any());
    }

    class StubOrganizer extends ITaskOrganizer.Stub {
        RunningTaskInfo mInfo;

        @Override
        public void addStartingWindow(ActivityManager.RunningTaskInfo info, IBinder appToken) { }
        @Override
        public void removeStartingWindow(ActivityManager.RunningTaskInfo info) { }
        @Override
        public void onTaskAppeared(RunningTaskInfo info, SurfaceControl leash) {
            mInfo = info;
        }
        @Override
        public void onTaskVanished(RunningTaskInfo info) {
        }
        @Override
        public void onTaskInfoChanged(RunningTaskInfo info) {
        }
        @Override
        public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        }
    };

    private ActivityRecord makePipableActivity() {
        final ActivityRecord record = createActivityRecordWithParentTask(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        record.info.flags |= ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        spyOn(record);
        doReturn(true).when(record).checkEnterPictureInPictureState(any(), anyBoolean());

        record.getTask().setHasBeenVisible(true);
        return record;
    }

    @Test
    public void testEnterPipParams() {
        final StubOrganizer o = new StubOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);
        final ActivityRecord record = makePipableActivity();

        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.enterPictureInPictureMode(record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);
    }

    @Test
    public void testChangePipParams() {
        class ChangeSavingOrganizer extends StubOrganizer {
            RunningTaskInfo mChangedInfo;
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                mChangedInfo = info;
            }
        }
        ChangeSavingOrganizer o = new ChangeSavingOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);

        final ActivityRecord record = makePipableActivity();
        final PictureInPictureParams p = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 2)).build();
        assertTrue(mWm.mAtmService.enterPictureInPictureMode(record.token, p));
        waitUntilHandlersIdle();
        assertNotNull(o.mInfo);
        assertNotNull(o.mInfo.pictureInPictureParams);

        final PictureInPictureParams p2 = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(3, 4)).build();
        mWm.mAtmService.setPictureInPictureParams(record.token, p2);
        waitUntilHandlersIdle();
        assertNotNull(o.mChangedInfo);
        assertNotNull(o.mChangedInfo.pictureInPictureParams);
        final Rational ratio = o.mChangedInfo.pictureInPictureParams.getAspectRatioRational();
        assertEquals(3, ratio.getNumerator());
        assertEquals(4, ratio.getDenominator());
    }

    @Test
    public void testChangeTaskDescription() {
        class ChangeSavingOrganizer extends StubOrganizer {
            RunningTaskInfo mChangedInfo;
            @Override
            public void onTaskInfoChanged(RunningTaskInfo info) {
                mChangedInfo = info;
            }
        }
        ChangeSavingOrganizer o = new ChangeSavingOrganizer();
        mWm.mAtmService.mTaskOrganizerController.registerTaskOrganizer(o);

        final Task stack = createStack();
        final Task task = createTask(stack);
        final ActivityRecord record = createActivityRecord(stack.mDisplayContent, task);

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        record.setTaskDescription(new ActivityManager.TaskDescription("TestDescription"));
        waitUntilHandlersIdle();
        assertEquals("TestDescription", o.mChangedInfo.taskDescription.getLabel());
    }

    @Test
    public void testPreventDuplicateAppear() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack, false /* fakeDraw */);

        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        stack.setTaskOrganizer(organizer);
        // setHasBeenVisible was already called once by the set-up code.
        stack.setHasBeenVisible(true);
        verify(organizer, times(1))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        stack.setTaskOrganizer(null);
        verify(organizer, times(1)).onTaskVanished(any());
        stack.setTaskOrganizer(organizer);
        verify(organizer, times(2))
                .onTaskAppeared(any(RunningTaskInfo.class), any(SurfaceControl.class));

        stack.removeImmediately();
        verify(organizer, times(2)).onTaskVanished(any());
    }

    @Test
    public void testInterceptBackPressedOnTaskRoot() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer();
        final Task stack = createStack();
        final Task task = createTask(stack);
        final ActivityRecord activity = createActivityRecord(stack.mDisplayContent, task);
        final Task stack2 = createStack();
        final Task task2 = createTask(stack2);
        final ActivityRecord activity2 = createActivityRecord(stack.mDisplayContent, task2);

        assertTrue(stack.isOrganized());
        assertTrue(stack2.isOrganized());

        // Verify a back pressed does not call the organizer
        mWm.mAtmService.onBackPressedOnTaskRoot(activity.token);
        verify(organizer, never()).onBackPressedOnTaskRoot(any());

        // Enable intercepting back
        mWm.mAtmService.mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(
                stack.mRemoteToken.toWindowContainerToken(), true);

        // Verify now that the back press does call the organizer
        mWm.mAtmService.onBackPressedOnTaskRoot(activity.token);
        verify(organizer, times(1)).onBackPressedOnTaskRoot(any());

        // Disable intercepting back
        mWm.mAtmService.mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(
                stack.mRemoteToken.toWindowContainerToken(), false);

        // Verify now that the back press no longer calls the organizer
        mWm.mAtmService.onBackPressedOnTaskRoot(activity.token);
        verify(organizer, times(1)).onBackPressedOnTaskRoot(any());
    }

    @Test
    public void testBLASTCallbackWithWindows() throws Exception {
        final Task stackController = createStack();
        final Task task = createTask(stackController);
        final WindowState w1 = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window 1");
        final WindowState w2 = createAppWindow(task, TYPE_APPLICATION, "Enlightened Window 2");
        makeWindowVisible(w1);
        makeWindowVisible(w2);

        IWindowContainerTransactionCallback mockCallback =
                mock(IWindowContainerTransactionCallback.class);
        int id = mWm.mAtmService.mWindowOrganizerController.startSyncWithOrganizer(mockCallback);

        mWm.mAtmService.mWindowOrganizerController.addToSyncSet(id, task);
        mWm.mAtmService.mWindowOrganizerController.setSyncReady(id);

        // Since we have a window we have to wait for it to draw to finish sync.
        verify(mockCallback, never()).onTransactionReady(anyInt(), any());
        assertTrue(w1.useBLASTSync());
        assertTrue(w2.useBLASTSync());

        // Make second (bottom) ready. If we started with the top, since activities fillsParent
        // by default, the sync would be considered finished.
        w2.immediatelyNotifyBlastSync();
        mWm.mSyncEngine.onSurfacePlacement();
        verify(mockCallback, never()).onTransactionReady(anyInt(), any());

        assertEquals(SYNC_STATE_READY, w2.mSyncState);
        // Even though one Window finished drawing, both windows should still be using blast sync
        assertTrue(w1.useBLASTSync());
        assertTrue(w2.useBLASTSync());

        w1.immediatelyNotifyBlastSync();
        mWm.mSyncEngine.onSurfacePlacement();
        verify(mockCallback).onTransactionReady(anyInt(), any());
        assertFalse(w1.useBLASTSync());
        assertFalse(w2.useBLASTSync());
    }

    @Test
    public void testDisplayAreaHiddenTransaction() {
        removeGlobalMinSizeRestriction();

        WindowContainerTransaction trx = new WindowContainerTransaction();

        TaskDisplayArea taskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();

        trx.setHidden(taskDisplayArea.mRemoteToken.toWindowContainerToken(), true);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(trx);

        taskDisplayArea.forAllTasks(daTask -> {
            assertTrue(daTask.isForceHidden());
        });

        trx.setHidden(taskDisplayArea.mRemoteToken.toWindowContainerToken(), false);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(trx);

        taskDisplayArea.forAllTasks(daTask -> {
            assertFalse(daTask.isForceHidden());
        });
    }

    @Test
    public void testReparentToOrganizedTask() {
        final ITaskOrganizer organizer = registerMockOrganizer();
        Task rootTask = mWm.mAtmService.mTaskOrganizerController.createRootTask(
                mDisplayContent, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, null);
        final Task task1 = createStack();
        final Task task2 = createTask(rootTask, false /* fakeDraw */);
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(task1.mRemoteToken.toWindowContainerToken(),
                rootTask.mRemoteToken.toWindowContainerToken(), true /* onTop */);
        mWm.mAtmService.mWindowOrganizerController.applyTransaction(wct);
        assertTrue(task1.isOrganized());
        assertTrue(task2.isOrganized());
    }

    /**
     * Verifies that task vanished is called for a specific task.
     */
    private void assertTaskVanished(ITaskOrganizer organizer, boolean expectVanished, Task... tasks)
            throws RemoteException {
        ArgumentCaptor<RunningTaskInfo> arg = ArgumentCaptor.forClass(RunningTaskInfo.class);
        verify(organizer, atLeastOnce()).onTaskVanished(arg.capture());
        List<RunningTaskInfo> taskInfos = arg.getAllValues();

        HashSet<Integer> vanishedTaskIds = new HashSet<>();
        for (int i = 0; i < taskInfos.size(); i++) {
            vanishedTaskIds.add(taskInfos.get(i).taskId);
        }
        HashSet<Integer> taskIds = new HashSet<>();
        for (int i = 0; i < tasks.length; i++) {
            taskIds.add(tasks[i].mTaskId);
        }

        assertTrue(expectVanished
                ? vanishedTaskIds.containsAll(taskIds)
                : !vanishedTaskIds.removeAll(taskIds));
    }

    private void assertContainsTasks(List<TaskAppearedInfo> taskInfos, Task... expectedTasks) {
        HashSet<Integer> taskIds = new HashSet<>();
        for (int i = 0; i < taskInfos.size(); i++) {
            taskIds.add(taskInfos.get(i).getTaskInfo().taskId);
        }
        for (int i = 0; i < expectedTasks.length; i++) {
            assertTrue(taskIds.contains(expectedTasks[i].mTaskId));
        }
    }
}
