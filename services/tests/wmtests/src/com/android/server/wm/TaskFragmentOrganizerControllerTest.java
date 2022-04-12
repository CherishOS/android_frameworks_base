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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.testing.Assert.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TaskFragmentOrganizerToken;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentOrganizerControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentOrganizerControllerTest extends WindowTestsBase {
    private static final int TASK_ID = 10;

    private TaskFragmentOrganizerController mController;
    private TaskFragmentOrganizer mOrganizer;
    private TaskFragmentOrganizerToken mOrganizerToken;
    private ITaskFragmentOrganizer mIOrganizer;
    private TaskFragment mTaskFragment;
    private TaskFragmentInfo mTaskFragmentInfo;
    private IBinder mFragmentToken;
    private WindowContainerTransaction mTransaction;
    private WindowContainerToken mFragmentWindowToken;
    private RemoteAnimationDefinition mDefinition;

    @Before
    public void setup() {
        mController = mAtm.mWindowOrganizerController.mTaskFragmentOrganizerController;
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        mOrganizerToken = mOrganizer.getOrganizerToken();
        mIOrganizer = ITaskFragmentOrganizer.Stub.asInterface(mOrganizerToken.asBinder());
        mTaskFragmentInfo = mock(TaskFragmentInfo.class);
        mFragmentToken = new Binder();
        mTaskFragment =
                new TaskFragment(mAtm, mFragmentToken, true /* createdByOrganizer */);
        mTransaction = new WindowContainerTransaction();
        mFragmentWindowToken = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mDefinition = new RemoteAnimationDefinition();

        spyOn(mController);
        spyOn(mOrganizer);
        spyOn(mTaskFragment);
        doReturn(mIOrganizer).when(mTaskFragment).getTaskFragmentOrganizer();
        doReturn(mTaskFragmentInfo).when(mTaskFragment).getTaskFragmentInfo();
        doReturn(new SurfaceControl()).when(mTaskFragment).getSurfaceControl();
        doReturn(mFragmentToken).when(mTaskFragment).getFragmentToken();
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();
    }

    @Test
    public void testCallTaskFragmentCallbackWithoutRegister_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentInfoChanged(
                        mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentParentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                        mTaskFragment));
    }

    @Test
    public void testOnTaskFragmentAppeared() {
        mController.registerOrganizer(mIOrganizer);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentAppeared(any());
    }

    @Test
    public void testOnTaskFragmentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        // No callback if the info is not changed.
        doReturn(true).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());

        // Trigger callback if the info is changed.
        doReturn(false).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentInfoChanged(mTaskFragmentInfo);
    }

    @Test
    public void testOnTaskFragmentVanished() {
        mController.registerOrganizer(mIOrganizer);

        mTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentVanished(any());
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        final Task parent = mock(Task.class);
        final Configuration parentConfig = new Configuration();
        parentConfig.smallestScreenWidthDp = 10;
        doReturn(parent).when(mTaskFragment).getParent();
        doReturn(parentConfig).when(parent).getConfiguration();
        doReturn(parent).when(parent).asTask();

        mTaskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());

        // No extra callback if the info is not changed.
        clearInvocations(mOrganizer);

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer, never()).onTaskFragmentParentInfoChanged(any(), any());

        // Trigger callback if the info is changed.
        parentConfig.smallestScreenWidthDp = 100;

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());
    }

    @Test
    public void testOnTaskFragmentError() throws RemoteException {
        final IBinder errorCallbackToken = new Binder();
        final Throwable exception = new IllegalArgumentException("Test exception");

        mController.registerOrganizer(mIOrganizer);
        mController.onTaskFragmentError(mTaskFragment.getTaskFragmentOrganizer(),
                errorCallbackToken, exception);
        mController.dispatchPendingEvents();

        verify(mOrganizer).onTaskFragmentError(eq(errorCallbackToken), eq(exception));
    }

    @Test
    public void testRegisterRemoteAnimations() {
        mController.registerOrganizer(mIOrganizer);
        mController.registerRemoteAnimations(mIOrganizer, TASK_ID, mDefinition);

        assertEquals(mDefinition, mController.getRemoteAnimationDefinition(mIOrganizer, TASK_ID));

        mController.unregisterRemoteAnimations(mIOrganizer, TASK_ID);

        assertNull(mController.getRemoteAnimationDefinition(mIOrganizer, TASK_ID));
    }

    @Test
    public void testWindowContainerTransaction_setTaskFragmentOrganizer() {
        mOrganizer.applyTransaction(mTransaction);

        assertEquals(mIOrganizer, mTransaction.getTaskFragmentOrganizer());

        mTransaction = new WindowContainerTransaction();
        mOrganizer.applySyncTransaction(
                mTransaction, mock(WindowContainerTransactionCallback.class));

        assertEquals(mIOrganizer, mTransaction.getTaskFragmentOrganizer());
    }

    @Test
    public void testApplyTransaction_enforceConfigurationChangeOnOrganizedTaskFragment()
            throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.setBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_reorder() throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.reorder(mFragmentWindowToken, true /* onTop */);

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);

        assertApplyTransactionAllowed(mTransaction);
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_deleteTaskFragment()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        mOrganizer.applyTransaction(mTransaction);
        doReturn(true).when(mTaskFragment).isAttached();

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.deleteTaskFragment(mFragmentWindowToken);

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        assertApplyTransactionAllowed(mTransaction);

        // No lifecycle update when the TaskFragment is not recorded.
        verify(mAtm.mRootWindowContainer, never()).resumeFocusedTasksTopActivities();

        mAtm.mWindowOrganizerController.mLaunchTaskFragments
                .put(mFragmentToken, mTaskFragment);
        assertApplyTransactionAllowed(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_setAdjacentRoots()
            throws RemoteException {
        final TaskFragment taskFragment2 =
                new TaskFragment(mAtm, new Binder(), true /* createdByOrganizer */);
        final WindowContainerToken token2 = taskFragment2.mRemoteToken.toWindowContainerToken();
        mOrganizer.applyTransaction(mTransaction);

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.setAdjacentRoots(mFragmentWindowToken, token2, false /* moveTogether */);

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        taskFragment2.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        assertApplyTransactionAllowed(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_createTaskFragment()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);
        final IBinder fragmentToken = new Binder();

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, fragmentToken);
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, null /* callerToken */, new Intent(), null /* activityOptions */);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, mock(IBinder.class));
        mTransaction.setAdjacentTaskFragments(mFragmentToken, mock(IBinder.class),
                null /* options */);
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        // Successfully created a TaskFragment.
        final TaskFragment taskFragment = mAtm.mWindowOrganizerController
                .getTaskFragment(fragmentToken);
        assertNotNull(taskFragment);
        assertEquals(ownerActivity.getTask(), taskFragment.getTask());
    }

    @Test
    public void testApplyTransaction_createTaskFragment_failForDifferentUid()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final int uid = Binder.getCallingUid();
        final IBinder fragmentToken = new Binder();
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, activity.token).build();
        mOrganizer.applyTransaction(mTransaction);
        mTransaction.createTaskFragment(params);

        // Fail to create TaskFragment when the task uid is different from caller.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid + 1;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Fail to create TaskFragment when the task uid is different from owner activity.
        activity.info.applicationInfo.uid = uid + 1;
        activity.getTask().effectiveUid = uid;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));

        // Successfully created a TaskFragment for same uid.
        activity.info.applicationInfo.uid = uid;
        activity.getTask().effectiveUid = uid;
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        assertNotNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testApplyTransaction_enforceHierarchyChange_reparentChildren()
            throws RemoteException {
        mOrganizer.applyTransaction(mTransaction);
        mController.registerOrganizer(mIOrganizer);
        doReturn(true).when(mTaskFragment).isAttached();

        // Throw exception if the transaction is trying to change a window that is not organized by
        // the organizer.
        mTransaction.reparentChildren(mFragmentWindowToken, null /* newParent */);

        assertApplyTransactionDisallowed(mTransaction);

        // Allow transaction to change a TaskFragment created by the organizer.
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        clearInvocations(mAtm.mRootWindowContainer);

        assertApplyTransactionAllowed(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testApplyTransaction_reparentActivityToTaskFragment_triggerLifecycleUpdate()
            throws RemoteException {
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        mOrganizer.applyTransaction(mTransaction);
        mController.registerOrganizer(mIOrganizer);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setFragmentToken(mFragmentToken)
                .build();
        mAtm.mWindowOrganizerController.mLaunchTaskFragments
                .put(mFragmentToken, mTaskFragment);
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token);
        doReturn(true).when(mTaskFragment).isAllowedToEmbedActivity(activity);
        clearInvocations(mAtm.mRootWindowContainer);

        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);

        verify(mAtm.mRootWindowContainer).resumeFocusedTasksTopActivities();
    }

    @Test
    public void testTaskFragmentInPip_startActivityInTaskFragment() {
        setupTaskFragmentInPip();
        final ActivityRecord activity = mTaskFragment.getTopMostActivity();
        final IBinder errorToken = new Binder();
        spyOn(mAtm.getActivityStartController());
        spyOn(mAtm.mWindowOrganizerController);

        // Not allow to start activity in a TaskFragment that is in a PIP Task.
        mTransaction.startActivityInTaskFragment(
                mFragmentToken, activity.token, new Intent(), null /* activityOptions */)
                .setErrorCallbackToken(errorToken);
        mAtm.mWindowOrganizerController.applyTransaction(mTransaction);

        verify(mAtm.getActivityStartController(), never()).startActivityInTaskFragment(any(), any(),
                any(), any(), anyInt(), anyInt());
        verify(mAtm.mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(errorToken), any(IllegalArgumentException.class));
    }

    @Test
    public void testTaskFragmentInPip_reparentActivityToTaskFragment() {
        setupTaskFragmentInPip();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final IBinder errorToken = new Binder();
        spyOn(mAtm.mWindowOrganizerController);

        // Not allow to reparent activity to a TaskFragment that is in a PIP Task.
        mTransaction.reparentActivityToTaskFragment(mFragmentToken, activity.token)
                .setErrorCallbackToken(errorToken);
        mAtm.mWindowOrganizerController.applyTransaction(mTransaction);

        verify(mAtm.mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(errorToken), any(IllegalArgumentException.class));
        assertNull(activity.getOrganizedTaskFragment());
    }

    @Test
    public void testTaskFragmentInPip_setAdjacentTaskFragment() {
        setupTaskFragmentInPip();
        final IBinder errorToken = new Binder();
        spyOn(mAtm.mWindowOrganizerController);

        // Not allow to set adjacent on a TaskFragment that is in a PIP Task.
        mTransaction.setAdjacentTaskFragments(mFragmentToken, null /* fragmentToken2 */,
                null /* options */)
                .setErrorCallbackToken(errorToken);
        mAtm.mWindowOrganizerController.applyTransaction(mTransaction);

        verify(mAtm.mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(errorToken), any(IllegalArgumentException.class));
        verify(mTaskFragment, never()).setAdjacentTaskFragment(any(), anyBoolean());
    }

    @Test
    public void testTaskFragmentInPip_createTaskFragment() {
        mController.registerOrganizer(mIOrganizer);
        final Task pipTask = createTask(mDisplayContent, WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD);
        final ActivityRecord activity = createActivityRecord(pipTask);
        final IBinder fragmentToken = new Binder();
        final IBinder errorToken = new Binder();
        spyOn(mAtm.mWindowOrganizerController);

        // Not allow to create TaskFragment in a PIP Task.
        createTaskFragmentFromOrganizer(mTransaction, activity, fragmentToken);
        mTransaction.setErrorCallbackToken(errorToken);
        mAtm.mWindowOrganizerController.applyTransaction(mTransaction);

        verify(mAtm.mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(errorToken), any(IllegalArgumentException.class));
        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    @Test
    public void testTaskFragmentInPip_deleteTaskFragment() {
        setupTaskFragmentInPip();
        final IBinder errorToken = new Binder();
        spyOn(mAtm.mWindowOrganizerController);

        // Not allow to delete a TaskFragment that is in a PIP Task.
        mTransaction.deleteTaskFragment(mFragmentWindowToken)
                .setErrorCallbackToken(errorToken);
        mAtm.mWindowOrganizerController.applyTransaction(mTransaction);

        verify(mAtm.mWindowOrganizerController).sendTaskFragmentOperationFailure(eq(mIOrganizer),
                eq(errorToken), any(IllegalArgumentException.class));
        assertNotNull(mAtm.mWindowOrganizerController.getTaskFragment(mFragmentToken));
    }

    @Test
    public void testTaskFragmentInPip_setConfig() {
        setupTaskFragmentInPip();
        spyOn(mAtm.mWindowOrganizerController);

        // Set bounds is ignored on a TaskFragment that is in a PIP Task.
        mTransaction.setBounds(mFragmentWindowToken, new Rect(0, 0, 100, 100));

        verify(mTaskFragment, never()).setBounds(any());
    }

    @Test
    public void testDeferPendingTaskFragmentEventsOfInvisibleTask() {
        // Task - TaskFragment - Activity.
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .build();

        // Mock the task to invisible
        doReturn(false).when(task).shouldBeVisible(any());

        // Sending events
        mController.registerOrganizer(mIOrganizer);
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Verifies that event was not sent
        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());
    }

    @Test
    public void testCanSendPendingTaskFragmentEventsAfterActivityResumed() {
        // Task - TaskFragment - Activity.
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .createActivityCount(1)
                .build();
        final ActivityRecord activity = taskFragment.getTopMostActivity();

        // Mock the task to invisible
        doReturn(false).when(task).shouldBeVisible(any());
        taskFragment.setResumedActivity(null, "test");

        // Sending events
        mController.registerOrganizer(mIOrganizer);
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Verifies that event was not sent
        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());

        // Mock the task becomes visible, and activity resumed
        doReturn(true).when(task).shouldBeVisible(any());
        taskFragment.setResumedActivity(activity, "test");

        // Verifies that event is sent.
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTaskFragmentInfoChanged(any());
    }

    /**
     * Tests that a task fragment info changed event is still sent if the task is invisible only
     * when the info changed event is because of the last activity in a task finishing.
     */
    @Test
    public void testLastPendingTaskFragmentInfoChangedEventOfInvisibleTaskSent() {
        // Create a TaskFragment with an activity, all within a parent task
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setOrganizer(mOrganizer)
                .setFragmentToken(mFragmentToken)
                .setCreateParentTask()
                .createActivityCount(1)
                .build();
        final Task parentTask = taskFragment.getTask();
        final ActivityRecord activity = taskFragment.getTopNonFinishingActivity();
        assertTrue(parentTask.shouldBeVisible(null));

        // Dispatch pending info changed event from creating the activity
        mController.registerOrganizer(mIOrganizer);
        taskFragment.mTaskFragmentAppearedSent = true;
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();

        // Finish the activity and verify that the task is invisible
        activity.finishing = true;
        assertFalse(parentTask.shouldBeVisible(null));

        // Verify the info changed callback still occurred despite the task being invisible
        reset(mOrganizer);
        mController.onTaskFragmentInfoChanged(mIOrganizer, taskFragment);
        mController.dispatchPendingEvents();
        verify(mOrganizer).onTaskFragmentInfoChanged(any());
    }

    /**
     * When an embedded {@link TaskFragment} is removed, we should clean up the reference in the
     * {@link WindowOrganizerController}.
     */
    @Test
    public void testTaskFragmentRemoved_cleanUpEmbeddedTaskFragment()
            throws RemoteException {
        mController.registerOrganizer(mIOrganizer);
        final ActivityRecord ownerActivity = createActivityRecord(mDisplayContent);
        final IBinder fragmentToken = new Binder();
        createTaskFragmentFromOrganizer(mTransaction, ownerActivity, fragmentToken);
        mAtm.getWindowOrganizerController().applyTransaction(mTransaction);
        final TaskFragment taskFragment = mAtm.mWindowOrganizerController
                .getTaskFragment(fragmentToken);

        assertNotNull(taskFragment);

        taskFragment.removeImmediately();

        assertNull(mAtm.mWindowOrganizerController.getTaskFragment(fragmentToken));
    }

    /**
     * For config change to untrusted embedded TaskFragment, we only allow bounds change within
     * its parent bounds.
     */
    @Test
    public void testUntrustedEmbedding_configChange() throws RemoteException  {
        mController.registerOrganizer(mIOrganizer);
        mOrganizer.applyTransaction(mTransaction);
        mTaskFragment.setTaskFragmentOrganizer(mOrganizerToken, 10 /* uid */,
                "Test:TaskFragmentOrganizer" /* processName */);
        doReturn(false).when(mTaskFragment).isAllowedToBeEmbeddedInTrustedMode();
        final Task task = createTask(mDisplayContent);
        final Rect taskBounds = new Rect(task.getBounds());
        final Rect taskAppBounds = new Rect(task.getWindowConfiguration().getAppBounds());
        final int taskScreenWidthDp = task.getConfiguration().screenWidthDp;
        final int taskScreenHeightDp = task.getConfiguration().screenHeightDp;
        final int taskSmallestScreenWidthDp = task.getConfiguration().smallestScreenWidthDp;
        task.addChild(mTaskFragment, POSITION_TOP);

        // Throw exception if the transaction is trying to change bounds of an untrusted outside of
        // its parent's.

        // setBounds
        final Rect tfBounds = new Rect(taskBounds);
        tfBounds.right++;
        mTransaction.setBounds(mFragmentWindowToken, tfBounds);
        assertApplyTransactionDisallowed(mTransaction);

        mTransaction.setBounds(mFragmentWindowToken, taskBounds);
        assertApplyTransactionAllowed(mTransaction);

        // setAppBounds
        final Rect tfAppBounds = new Rect(taskAppBounds);
        tfAppBounds.right++;
        mTransaction.setAppBounds(mFragmentWindowToken, tfAppBounds);
        assertApplyTransactionDisallowed(mTransaction);

        mTransaction.setAppBounds(mFragmentWindowToken, taskAppBounds);
        assertApplyTransactionAllowed(mTransaction);

        // setScreenSizeDp
        mTransaction.setScreenSizeDp(mFragmentWindowToken, taskScreenWidthDp + 1,
                taskScreenHeightDp + 1);
        assertApplyTransactionDisallowed(mTransaction);

        mTransaction.setScreenSizeDp(mFragmentWindowToken, taskScreenWidthDp, taskScreenHeightDp);
        assertApplyTransactionAllowed(mTransaction);

        // setSmallestScreenWidthDp
        mTransaction.setSmallestScreenWidthDp(mFragmentWindowToken, taskSmallestScreenWidthDp + 1);
        assertApplyTransactionDisallowed(mTransaction);

        mTransaction.setSmallestScreenWidthDp(mFragmentWindowToken, taskSmallestScreenWidthDp);
        assertApplyTransactionAllowed(mTransaction);

        // Any of the change mask is not allowed.
        mTransaction.setFocusable(mFragmentWindowToken, false);
        assertApplyTransactionDisallowed(mTransaction);
    }

    /**
     * Creates a {@link TaskFragment} with the {@link WindowContainerTransaction}. Calls
     * {@link WindowOrganizerController#applyTransaction} to apply the transaction,
     */
    private void createTaskFragmentFromOrganizer(WindowContainerTransaction wct,
            ActivityRecord ownerActivity, IBinder fragmentToken) {
        final int uid = Binder.getCallingUid();
        ownerActivity.info.applicationInfo.uid = uid;
        ownerActivity.getTask().effectiveUid = uid;
        final TaskFragmentCreationParams params = new TaskFragmentCreationParams.Builder(
                mOrganizerToken, fragmentToken, ownerActivity.token).build();
        mOrganizer.applyTransaction(wct);

        // Allow organizer to create TaskFragment and start/reparent activity to TaskFragment.
        wct.createTaskFragment(params);
    }

    /** Asserts that applying the given transaction will throw a {@link SecurityException}. */
    private void assertApplyTransactionDisallowed(WindowContainerTransaction t) {
        assertThrows(SecurityException.class, () -> {
            try {
                mAtm.getWindowOrganizerController().applyTransaction(t);
            } catch (RemoteException e) {
                fail();
            }
        });
    }

    /** Asserts that applying the given transaction will not throw any exception. */
    private void assertApplyTransactionAllowed(WindowContainerTransaction t) {
        try {
            mAtm.getWindowOrganizerController().applyTransaction(t);
        } catch (RemoteException e) {
            fail();
        }
    }

    /** Setups an embedded TaskFragment in a PIP Task. */
    private void setupTaskFragmentInPip() {
        mOrganizer.applyTransaction(mTransaction);
        mController.registerOrganizer(mIOrganizer);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setFragmentToken(mFragmentToken)
                .setOrganizer(mOrganizer)
                .createActivityCount(1)
                .build();
        mFragmentWindowToken = mTaskFragment.mRemoteToken.toWindowContainerToken();
        mAtm.mWindowOrganizerController.mLaunchTaskFragments
                .put(mFragmentToken, mTaskFragment);
        mTaskFragment.getTask().setWindowingMode(WINDOWING_MODE_PINNED);
    }
}
