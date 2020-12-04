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

import static android.content.ActivityInfoProto.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOWED_MAGNIFICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.SizeCompatTests.prepareUnresizable;
import static com.android.server.wm.SizeCompatTests.rotateDisplay;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.Display;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the Dual DisplayAreaGroup device behavior.
 *
 * Build/Install/Run:
 *  atest WmTests:DualDisplayAreaGroupPolicyTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DualDisplayAreaGroupPolicyTest extends WindowTestsBase {
    private static final int FEATURE_FIRST_ROOT = FEATURE_VENDOR_FIRST;
    private static final int FEATURE_FIRST_TASK_CONTAINER = FEATURE_DEFAULT_TASK_CONTAINER;
    private static final int FEATURE_SECOND_ROOT = FEATURE_VENDOR_FIRST + 1;
    private static final int FEATURE_SECOND_TASK_CONTAINER = FEATURE_VENDOR_FIRST + 2;

    private DualDisplayContent mDisplay;
    private DisplayAreaGroup mFirstRoot;
    private DisplayAreaGroup mSecondRoot;
    private TaskDisplayArea mFirstTda;
    private TaskDisplayArea mSecondTda;
    private Task mFirstTask;
    private Task mSecondTask;
    private ActivityRecord mFirstActivity;
    private ActivityRecord mSecondActivity;

    @Before
    public void setUp() {
        // Let the Display to be created with the DualDisplay policy.
        final DisplayAreaPolicy.Provider policyProvider = new DualDisplayTestPolicyProvider();
        doReturn(policyProvider).when(mWm).getDisplayAreaPolicyProvider();

        // Display: 1920x1200 (landscape). First and second display are both 860x1200 (portrait).
        mDisplay = (DualDisplayContent) new DualDisplayContent.Builder(mAtm, 1920, 1200).build();
        mFirstRoot = mDisplay.mFirstRoot;
        mSecondRoot = mDisplay.mSecondRoot;
        mFirstTda = mDisplay.getTaskDisplayArea(FEATURE_FIRST_TASK_CONTAINER);
        mSecondTda = mDisplay.getTaskDisplayArea(FEATURE_SECOND_TASK_CONTAINER);
        mFirstTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(mFirstTda)
                .setCreateActivity(true)
                .build()
                .getBottomMostTask();
        mSecondTask = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(mSecondTda)
                .setCreateActivity(true)
                .build()
                .getBottomMostTask();
        mFirstActivity = mFirstTask.getTopNonFinishingActivity();
        mSecondActivity = mSecondTask.getTopNonFinishingActivity();

        spyOn(mDisplay);
        spyOn(mFirstRoot);
        spyOn(mSecondRoot);
    }

    @Test
    public void testNotIgnoreOrientationRequest_differentOrientationFromDisplay_reversesRequest() {
        mFirstRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_LANDSCAPE);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(mFirstActivity.getConfiguration().orientation).isEqualTo(ORIENTATION_PORTRAIT);
    }

    @Test
    public void testNotIgnoreOrientationRequest_onlyRespectsFocusedTaskDisplayArea() {
        mFirstRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        // Second TDA is not focused, so Display won't get the request
        prepareUnresizable(mSecondActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_UNSPECIFIED);

        // First TDA is focused, so Display gets the request
        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        assertThat(mDisplay.getLastOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    public void testIgnoreOrientationRequest_displayDoesNotReceiveOrientationChange() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);

        verify(mFirstRoot).onDescendantOrientationChanged(any());
        verify(mDisplay, never()).onDescendantOrientationChanged(any());
    }

    @Test
    public void testLaunchPortraitApp_fillsDisplayAreaGroup() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect taskBounds = new Rect(mFirstTask.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is portrait (860x1200), so Task and Activity fill DAG.
        assertThat(mFirstTask.isTaskLetterboxed()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();
        assertThat(taskBounds).isEqualTo(dagBounds);
        assertThat(activityBounds).isEqualTo(taskBounds);
    }

    @Test
    public void testLaunchPortraitApp_sizeCompatAfterRotation() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_PORTRAIT);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        rotateDisplay(mDisplay, ROTATION_90);
        final Rect newDagBounds = new Rect(mFirstRoot.getBounds());
        final Rect newTaskBounds = new Rect(mFirstTask.getBounds());
        final Rect activitySizeCompatBounds = new Rect(mFirstActivity.getBounds());
        final Rect activityConfigBounds =
                new Rect(mFirstActivity.getConfiguration().windowConfiguration.getBounds());

        // DAG is landscape (1200x860), Task fills parent
        assertThat(mFirstTask.isTaskLetterboxed()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isTrue();
        assertThat(newDagBounds.width()).isEqualTo(dagBounds.height());
        assertThat(newDagBounds.height()).isEqualTo(dagBounds.width());
        assertThat(newTaskBounds).isEqualTo(newDagBounds);

        // Activity config bounds is unchanged, size compat bounds is (860x[860x860/1200=616])
        assertThat(mFirstActivity.getSizeCompatScale()).isLessThan(1f);
        assertThat(activityConfigBounds.width()).isEqualTo(activityBounds.width());
        assertThat(activityConfigBounds.height()).isEqualTo(activityBounds.height());
        assertThat(activitySizeCompatBounds.height()).isEqualTo(newTaskBounds.height());
        assertThat(activitySizeCompatBounds.width()).isEqualTo(
                newTaskBounds.height() * newTaskBounds.height() / newTaskBounds.width());
    }

    @Test
    public void testLaunchLandscapeApp_taskIsLetterboxInDisplayAreaGroup() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect taskBounds = new Rect(mFirstTask.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is portrait (860x1200), so Task is letterbox (860x[860x860/1200=616])
        assertThat(mFirstTask.isTaskLetterboxed()).isTrue();
        assertThat(mFirstActivity.inSizeCompatMode()).isFalse();
        assertThat(taskBounds.width()).isEqualTo(dagBounds.width());
        assertThat(taskBounds.height())
                .isEqualTo(dagBounds.width() * dagBounds.width() / dagBounds.height());
        assertThat(activityBounds).isEqualTo(taskBounds);
    }

    @Test
    public void testLaunchLandscapeApp_taskLetterboxBecomesActivityLetterboxAfterRotation() {
        mFirstRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mSecondRoot.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);
        mDisplay.onLastFocusedTaskDisplayAreaChanged(mFirstTda);

        prepareUnresizable(mFirstActivity, SCREEN_ORIENTATION_LANDSCAPE);
        final Rect dagBounds = new Rect(mFirstRoot.getBounds());
        final Rect activityBounds = new Rect(mFirstActivity.getBounds());

        rotateDisplay(mDisplay, ROTATION_90);
        final Rect newDagBounds = new Rect(mFirstRoot.getBounds());
        final Rect newTaskBounds = new Rect(mFirstTask.getBounds());
        final Rect newActivityBounds = new Rect(mFirstActivity.getBounds());

        // DAG is landscape (1200x860), Task fills parent
        // Task letterbox size
        assertThat(mFirstTask.isTaskLetterboxed()).isFalse();
        assertThat(mFirstActivity.inSizeCompatMode()).isTrue();
        assertThat(newDagBounds.width()).isEqualTo(dagBounds.height());
        assertThat(newDagBounds.height()).isEqualTo(dagBounds.width());
        assertThat(newTaskBounds).isEqualTo(newDagBounds);

        // Because we don't scale up, there is no size compat bounds and app bounds is the same as
        // the previous bounds.
        assertThat(mFirstActivity.hasSizeCompatBounds()).isFalse();
        assertThat(newActivityBounds.width()).isEqualTo(activityBounds.width());
        assertThat(newActivityBounds.height()).isEqualTo(activityBounds.height());
    }

    /** Display with two {@link DisplayAreaGroup}. Each of them take half of the screen. */
    private static class DualDisplayContent extends TestDisplayContent {
        final DisplayAreaGroup mFirstRoot;
        final DisplayAreaGroup mSecondRoot;
        final Rect mLastDisplayBounds;

        /** Please use the {@link Builder} to create. */
        DualDisplayContent(RootWindowContainer rootWindowContainer,
                Display display) {
            super(rootWindowContainer, display);

            mFirstRoot = getGroupRoot(FEATURE_FIRST_ROOT);
            mSecondRoot = getGroupRoot(FEATURE_SECOND_ROOT);
            mLastDisplayBounds = new Rect(getBounds());
            updateDisplayAreaGroupBounds();
        }

        DisplayAreaGroup getGroupRoot(int rootFeatureId) {
            DisplayArea da = getDisplayArea(rootFeatureId);
            assertThat(da).isInstanceOf(DisplayAreaGroup.class);
            return (DisplayAreaGroup) da;
        }

        TaskDisplayArea getTaskDisplayArea(int tdaFeatureId) {
            DisplayArea da = getDisplayArea(tdaFeatureId);
            assertThat(da).isInstanceOf(TaskDisplayArea.class);
            return (TaskDisplayArea) da;
        }

        DisplayArea getDisplayArea(int featureId) {
            final DisplayArea displayArea =
                    getItemFromDisplayAreas(da -> da.mFeatureId == featureId ? da : null);
            assertThat(displayArea).isNotNull();
            return displayArea;
        }

        @Override
        public void onConfigurationChanged(Configuration newParentConfig) {
            super.onConfigurationChanged(newParentConfig);

            final Rect curBounds = getBounds();
            if (mLastDisplayBounds != null && !mLastDisplayBounds.equals(curBounds)) {
                mLastDisplayBounds.set(curBounds);
                updateDisplayAreaGroupBounds();
            }
        }

        /** Updates first and second {@link DisplayAreaGroup} to take half of the screen. */
        private void updateDisplayAreaGroupBounds() {
            if (mFirstRoot == null || mSecondRoot == null) {
                return;
            }

            final Rect bounds = mLastDisplayBounds;
            Rect groupBounds1, groupBounds2;
            if (bounds.width() >= bounds.height()) {
                groupBounds1 = new Rect(bounds.left, bounds.top,
                        (bounds.right + bounds.left) / 2, bounds.bottom);

                groupBounds2 = new Rect((bounds.right + bounds.left) / 2, bounds.top,
                        bounds.right, bounds.bottom);
            } else {
                groupBounds1 = new Rect(bounds.left, bounds.top,
                        bounds.right, (bounds.top + bounds.bottom) / 2);

                groupBounds2 = new Rect(bounds.left,
                        (bounds.top + bounds.bottom) / 2, bounds.right, bounds.bottom);
            }
            mFirstRoot.setBounds(groupBounds1);
            mSecondRoot.setBounds(groupBounds2);
        }

        static class Builder extends TestDisplayContent.Builder {

            Builder(ActivityTaskManagerService service, int width, int height) {
                super(service, width, height);
            }

            @Override
            TestDisplayContent createInternal(Display display) {
                return new DualDisplayContent(mService.mRootWindowContainer, display);
            }
        }
    }

    /** Policy to create a dual {@link DisplayAreaGroup} policy in test. */
    private static class DualDisplayTestPolicyProvider implements DisplayAreaPolicy.Provider {

        @Override
        public DisplayAreaPolicy instantiate(WindowManagerService wmService, DisplayContent content,
                RootDisplayArea root, DisplayArea.Tokens imeContainer) {
            // Root
            // Include FEATURE_WINDOWED_MAGNIFICATION because it will be used as the screen rotation
            // layer
            DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
                            .setImeContainer(imeContainer)
                            .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(
                                    wmService.mPolicy,
                                    "WindowedMagnification", FEATURE_WINDOWED_MAGNIFICATION)
                                    .upTo(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                    .except(TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY)
                                    .setNewDisplayAreaSupplier(DisplayArea.Dimmable::new)
                                    .build());

            // First
            final RootDisplayArea firstRoot = new DisplayAreaGroup(wmService, "FirstRoot",
                    FEATURE_FIRST_ROOT);
            final TaskDisplayArea firstTaskDisplayArea = new TaskDisplayArea(content, wmService,
                    "FirstTaskDisplayArea", FEATURE_FIRST_TASK_CONTAINER);
            final List<TaskDisplayArea> firstTdaList = new ArrayList<>();
            firstTdaList.add(firstTaskDisplayArea);
            DisplayAreaPolicyBuilder.HierarchyBuilder firstHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(firstRoot)
                            .setTaskDisplayAreas(firstTdaList);

            // Second
            final RootDisplayArea secondRoot = new DisplayAreaGroup(wmService, "SecondRoot",
                    FEATURE_SECOND_ROOT);
            final TaskDisplayArea secondTaskDisplayArea = new TaskDisplayArea(content, wmService,
                    "SecondTaskDisplayArea", FEATURE_SECOND_TASK_CONTAINER);
            final List<TaskDisplayArea> secondTdaList = new ArrayList<>();
            secondTdaList.add(secondTaskDisplayArea);
            DisplayAreaPolicyBuilder.HierarchyBuilder secondHierarchy =
                    new DisplayAreaPolicyBuilder.HierarchyBuilder(secondRoot)
                            .setTaskDisplayAreas(secondTdaList);

            return new DisplayAreaPolicyBuilder()
                    .setRootHierarchy(rootHierarchy)
                    .addDisplayAreaGroupHierarchy(firstHierarchy)
                    .addDisplayAreaGroupHierarchy(secondHierarchy)
                    .build(wmService);
        }
    }
}
