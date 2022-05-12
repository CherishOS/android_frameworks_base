/*
 * Copyright (C) 2022 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.annotation.NonNull;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test class for {@link SplitController}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:SplitControllerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitControllerTest {
    private static final int TASK_ID = 10;
    private static final Rect TASK_BOUNDS = new Rect(0, 0, 600, 1200);
    private static final float SPLIT_RATIO = 0.5f;

    private Activity mActivity;
    @Mock
    private Resources mActivityResources;
    @Mock
    private TaskFragmentInfo mInfo;
    @Mock
    private WindowContainerTransaction mTransaction;
    @Mock
    private Handler mHandler;

    private SplitController mSplitController;
    private SplitPresenter mSplitPresenter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSplitController = new SplitController();
        mSplitPresenter = mSplitController.mPresenter;
        spyOn(mSplitController);
        spyOn(mSplitPresenter);
        final Configuration activityConfig = new Configuration();
        activityConfig.windowConfiguration.setBounds(TASK_BOUNDS);
        activityConfig.windowConfiguration.setMaxBounds(TASK_BOUNDS);
        doReturn(activityConfig).when(mActivityResources).getConfiguration();
        doReturn(mHandler).when(mSplitController).getHandler();
        mActivity = createMockActivity();
    }

    @Test
    public void testGetTopActiveContainer() {
        final TaskContainer taskContainer = new TaskContainer(TASK_ID);
        // tf1 has no running activity so is not active.
        final TaskFragmentContainer tf1 = new TaskFragmentContainer(null /* activity */,
                taskContainer, mSplitController);
        // tf2 has running activity so is active.
        final TaskFragmentContainer tf2 = mock(TaskFragmentContainer.class);
        doReturn(1).when(tf2).getRunningActivityCount();
        taskContainer.mContainers.add(tf2);
        // tf3 is finished so is not active.
        final TaskFragmentContainer tf3 = mock(TaskFragmentContainer.class);
        doReturn(true).when(tf3).isFinished();
        doReturn(false).when(tf3).isWaitingActivityAppear();
        taskContainer.mContainers.add(tf3);
        mSplitController.mTaskContainers.put(TASK_ID, taskContainer);

        assertWithMessage("Must return tf2 because tf3 is not active.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf3);

        assertWithMessage("Must return tf2 because tf2 has running activity.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf2);

        taskContainer.mContainers.remove(tf2);

        assertWithMessage("Must return tf because we are waiting for tf1 to appear.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf1);

        final TaskFragmentInfo info = mock(TaskFragmentInfo.class);
        doReturn(new ArrayList<>()).when(info).getActivities();
        doReturn(true).when(info).isEmpty();
        tf1.setInfo(info);

        assertWithMessage("Must return tf because we are waiting for tf1 to become non-empty after"
                + " creation.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isEqualTo(tf1);

        doReturn(false).when(info).isEmpty();
        tf1.setInfo(info);

        assertWithMessage("Must return null because tf1 becomes empty.")
                .that(mSplitController.getTopActiveContainer(TASK_ID)).isNull();
    }

    @Test
    public void testOnTaskFragmentVanished() {
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        doReturn(tf.getTaskFragmentToken()).when(mInfo).getFragmentToken();

        // The TaskFragment has been removed in the server, we only need to cleanup the reference.
        mSplitController.onTaskFragmentVanished(mInfo);

        verify(mSplitPresenter, never()).deleteTaskFragment(any(), any());
        verify(mSplitController).removeContainer(tf);
        verify(mActivity, never()).finish();
    }

    @Test
    public void testOnTaskFragmentAppearEmptyTimeout() {
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        mSplitController.onTaskFragmentAppearEmptyTimeout(tf);

        verify(mSplitPresenter).cleanupContainer(tf, false /* shouldFinishDependent */);
    }

    @Test
    public void testOnActivityDestroyed() {
        doReturn(new Binder()).when(mActivity).getActivityToken();
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);

        assertTrue(tf.hasActivity(mActivity.getActivityToken()));

        mSplitController.onActivityDestroyed(mActivity);

        assertFalse(tf.hasActivity(mActivity.getActivityToken()));
    }

    @Test
    public void testNewContainer() {
        // Must pass in a valid activity.
        assertThrows(IllegalArgumentException.class, () ->
                mSplitController.newContainer(null /* activity */, TASK_ID));
        assertThrows(IllegalArgumentException.class, () ->
                mSplitController.newContainer(mActivity, null /* launchingActivity */, TASK_ID));

        final TaskFragmentContainer tf = mSplitController.newContainer(null, mActivity, TASK_ID);
        final TaskContainer taskContainer = mSplitController.getTaskContainer(TASK_ID);

        assertNotNull(tf);
        assertNotNull(taskContainer);
        assertEquals(TASK_BOUNDS, taskContainer.getTaskBounds());
    }

    @Test
    public void testUpdateContainer() {
        // Make SplitController#launchPlaceholderIfNecessary(TaskFragmentContainer) return true
        // and verify if shouldContainerBeExpanded() not called.
        final TaskFragmentContainer tf = mSplitController.newContainer(mActivity, TASK_ID);
        spyOn(tf);
        doReturn(mActivity).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(tf).isEmpty();
        doReturn(true).when(mSplitController).launchPlaceholderIfNecessary(mActivity);
        doNothing().when(mSplitPresenter).updateSplitContainer(any(), any(), any());

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).shouldContainerBeExpanded(any());

        // Verify if tf should be expanded, getTopActiveContainer() won't be called
        doReturn(null).when(tf).getTopNonFinishingActivity();
        doReturn(true).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).getTopActiveContainer(TASK_ID);

        // Verify if tf is not in split, dismissPlaceholderIfNecessary won't be called.
        doReturn(false).when(mSplitController).shouldContainerBeExpanded(tf);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if tf is not in the top splitContainer,
        final SplitContainer splitContainer = mock(SplitContainer.class);
        doReturn(tf).when(splitContainer).getPrimaryContainer();
        doReturn(tf).when(splitContainer).getSecondaryContainer();
        final List<SplitContainer> splitContainers =
                mSplitController.getTaskContainer(TASK_ID).mSplitContainers;
        splitContainers.add(splitContainer);
        // Add a mock SplitContainer on top of splitContainer
        splitContainers.add(1, mock(SplitContainer.class));

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if one or both containers in the top SplitContainer are finished,
        // dismissPlaceholder() won't be called.
        splitContainers.remove(1);
        doReturn(true).when(tf).isFinished();

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitController, never()).dismissPlaceholderIfNecessary(any());

        // Verify if placeholder should be dismissed, updateSplitContainer() won't be called.
        doReturn(false).when(tf).isFinished();
        doReturn(true).when(mSplitController)
                .dismissPlaceholderIfNecessary(splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter, never()).updateSplitContainer(any(), any(), any());

        // Verify if the top active split is updated if both of its containers are not finished.
        doReturn(false).when(mSplitController)
                        .dismissPlaceholderIfNecessary(splitContainer);

        mSplitController.updateContainer(mTransaction, tf);

        verify(mSplitPresenter).updateSplitContainer(splitContainer, tf, mTransaction);
    }

    @Test
    public void testOnActivityReparentToTask_sameProcess() {
        mSplitController.onActivityReparentToTask(TASK_ID, new Intent(),
                mActivity.getActivityToken());

        // Treated as on activity created.
        verify(mSplitController).onActivityCreated(mActivity);
    }

    @Test
    public void testOnActivityReparentToTask_diffProcess() {
        // Create an empty TaskFragment to initialize for the Task.
        mSplitController.newContainer(null, mActivity, TASK_ID);
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent();

        mSplitController.onActivityReparentToTask(TASK_ID, intent, activityToken);

        // Treated as starting new intent
        verify(mSplitController, never()).onActivityCreated(mActivity);
        verify(mSplitController).resolveStartActivityIntent(any(), eq(TASK_ID), eq(intent),
                isNull());
    }

    @Test
    public void testResolveStartActivityIntent_withoutLaunchingActivity() {
        final Intent intent = new Intent();
        final ActivityRule expandRule = new ActivityRule.Builder(r -> false, i -> i == intent)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));

        // No other activity available in the Task.
        TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(mTransaction,
                TASK_ID, intent, null /* launchingActivity */);
        assertNull(container);

        // Task contains another activity that can be used as owner activity.
        createMockTaskFragmentContainer(mActivity);
        container = mSplitController.resolveStartActivityIntent(mTransaction,
                TASK_ID, intent, null /* launchingActivity */);
        assertNotNull(container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldExpand() {
        final Intent intent = new Intent();
        setupExpandRule(intent);
        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);

        assertNotNull(container);
        assertTrue(container.areLastRequestedBoundsEqual(null));
        assertTrue(container.isLastRequestedWindowingModeEqual(WINDOWING_MODE_UNDEFINED));
        assertFalse(container.hasActivity(mActivity.getActivityToken()));
        verify(mSplitPresenter).createTaskFragment(mTransaction, container.getTaskFragmentToken(),
                mActivity.getActivityToken(), new Rect(), WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithLaunchingActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, mActivity);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopExpandActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        createMockTaskFragmentContainer(mActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopSecondaryActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        final Activity primaryActivity = createMockActivity();
        addSplitTaskFragments(primaryActivity, mActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    @Test
    public void testResolveStartActivityIntent_shouldSplitWithTopPrimaryActivity() {
        final Intent intent = new Intent();
        setupSplitRule(mActivity, intent);
        final Activity secondaryActivity = createMockActivity();
        addSplitTaskFragments(mActivity, secondaryActivity);

        final TaskFragmentContainer container = mSplitController.resolveStartActivityIntent(
                mTransaction, TASK_ID, intent, null /* launchingActivity */);
        final TaskFragmentContainer primaryContainer = mSplitController.getContainerWithActivity(
                mActivity);

        assertSplitPair(primaryContainer, container);
    }

    /** Creates a mock activity in the organizer process. */
    private Activity createMockActivity() {
        final Activity activity = mock(Activity.class);
        doReturn(mActivityResources).when(activity).getResources();
        final IBinder activityToken = new Binder();
        doReturn(activityToken).when(activity).getActivityToken();
        doReturn(activity).when(mSplitController).getActivity(activityToken);
        return activity;
    }

    /** Creates a mock TaskFragmentInfo for the given TaskFragment. */
    private TaskFragmentInfo createMockTaskFragmentInfo(@NonNull TaskFragmentContainer container,
            @NonNull Activity activity) {
        return new TaskFragmentInfo(container.getTaskFragmentToken(),
                mock(WindowContainerToken.class),
                new Configuration(),
                1,
                true /* isVisible */,
                Collections.singletonList(activity.getActivityToken()),
                new Point(),
                false /* isTaskClearedForReuse */,
                false /* isTaskFragmentClearedForPip */);
    }

    /** Creates a mock TaskFragment that has been registered and appeared in the organizer. */
    private TaskFragmentContainer createMockTaskFragmentContainer(@NonNull Activity activity) {
        final TaskFragmentContainer container = mSplitController.newContainer(activity, TASK_ID);
        final TaskFragmentInfo info = createMockTaskFragmentInfo(container, activity);
        container.setInfo(createMockTaskFragmentInfo(container, activity));
        mSplitPresenter.mFragmentInfos.put(container.getTaskFragmentToken(), info);
        return container;
    }

    /** Setups a rule to always expand the given intent. */
    private void setupExpandRule(@NonNull Intent expandIntent) {
        final ActivityRule expandRule = new ActivityRule.Builder(r -> false, expandIntent::equals)
                .setShouldAlwaysExpand(true)
                .build();
        mSplitController.setEmbeddingRules(Collections.singleton(expandRule));
    }

    /** Setups a rule to always split the given activities. */
    private void setupSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent) {
        final SplitRule splitRule = createSplitRule(primaryActivity, secondaryIntent);
        mSplitController.setEmbeddingRules(Collections.singleton(splitRule));
    }

    /** Creates a rule to always split the given activity and the given intent. */
    private SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent) {
        final Pair<Activity, Intent> targetPair = new Pair<>(primaryActivity, secondaryIntent);
        return new SplitPairRule.Builder(
                activityPair -> false,
                targetPair::equals,
                w -> true)
                .setSplitRatio(SPLIT_RATIO)
                .setShouldClearTop(true)
                .build();
    }

    /** Creates a rule to always split the given activities. */
    private SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        final Pair<Activity, Activity> targetPair = new Pair<>(primaryActivity, secondaryActivity);
        return new SplitPairRule.Builder(
                targetPair::equals,
                activityIntentPair -> false,
                w -> true)
                .setSplitRatio(SPLIT_RATIO)
                .setShouldClearTop(true)
                .build();
    }

    /** Adds a pair of TaskFragments as split for the given activities. */
    private void addSplitTaskFragments(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        final TaskFragmentContainer primaryContainer = createMockTaskFragmentContainer(
                primaryActivity);
        final TaskFragmentContainer secondaryContainer = createMockTaskFragmentContainer(
                secondaryActivity);
        mSplitController.registerSplit(
                mock(WindowContainerTransaction.class),
                primaryContainer,
                primaryActivity,
                secondaryContainer,
                createSplitRule(primaryActivity, secondaryActivity));

        // We need to set those in case we are not respecting clear top.
        // TODO(b/231845476) we should always respect clearTop.
        final int windowingMode = mSplitController.getTaskContainer(TASK_ID)
                .getWindowingModeForSplitTaskFragment(TASK_BOUNDS);
        primaryContainer.setLastRequestedWindowingMode(windowingMode);
        secondaryContainer.setLastRequestedWindowingMode(windowingMode);
        primaryContainer.setLastRequestedBounds(getSplitBounds(true /* isPrimary */));
        secondaryContainer.setLastRequestedBounds(getSplitBounds(false /* isPrimary */));
    }

    /** Gets the bounds of a TaskFragment that is in split. */
    private Rect getSplitBounds(boolean isPrimary) {
        final int width = (int) (TASK_BOUNDS.width() * SPLIT_RATIO);
        return isPrimary
                ? new Rect(TASK_BOUNDS.left, TASK_BOUNDS.top, TASK_BOUNDS.left + width,
                        TASK_BOUNDS.bottom)
                : new Rect(TASK_BOUNDS.left + width, TASK_BOUNDS.top, TASK_BOUNDS.right,
                        TASK_BOUNDS.bottom);
    }

    /** Asserts that the two given TaskFragments are in split. */
    private void assertSplitPair(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer) {
        assertNotNull(primaryContainer);
        assertNotNull(secondaryContainer);
        assertTrue(primaryContainer.areLastRequestedBoundsEqual(
                getSplitBounds(true /* isPrimary */)));
        assertTrue(secondaryContainer.areLastRequestedBoundsEqual(
                getSplitBounds(false /* isPrimary */)));
        assertTrue(primaryContainer.isLastRequestedWindowingModeEqual(WINDOWING_MODE_MULTI_WINDOW));
        assertTrue(secondaryContainer.isLastRequestedWindowingModeEqual(
                WINDOWING_MODE_MULTI_WINDOW));
        assertNotNull(mSplitController.getActiveSplitForContainers(primaryContainer,
                secondaryContainer));
    }
}
