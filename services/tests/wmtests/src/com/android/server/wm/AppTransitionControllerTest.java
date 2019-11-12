/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:AppTransitionControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppTransitionControllerTest extends WindowTestsBase {

    private AppTransitionController mAppTransitionController;

    @Before
    public void setUp() throws Exception {
        mAppTransitionController = new AppTransitionController(mWm, mDisplayContent);
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTranslucentOpen() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentOpening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentOpening.setOccludesParent(false);
        translucentOpening.setVisible(false);
        mDisplayContent.mOpeningApps.add(behind);
        mDisplayContent.mOpeningApps.add(translucentOpening);
        assertEquals(WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN,
                mAppTransitionController.maybeUpdateTransitToTranslucentAnim(TRANSIT_TASK_OPEN));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTranslucentClose() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentClosing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentClosing.setOccludesParent(false);
        mDisplayContent.mClosingApps.add(translucentClosing);
        assertEquals(WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE,
                mAppTransitionController.maybeUpdateTransitToTranslucentAnim(TRANSIT_TASK_CLOSE));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testChangeIsNotOverwritten() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentOpening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentOpening.setOccludesParent(false);
        translucentOpening.setVisible(false);
        mDisplayContent.mOpeningApps.add(behind);
        mDisplayContent.mOpeningApps.add(translucentOpening);
        assertEquals(TRANSIT_TASK_CHANGE_WINDOWING_MODE,
                mAppTransitionController.maybeUpdateTransitToTranslucentAnim(
                        TRANSIT_TASK_CHANGE_WINDOWING_MODE));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTransitWithinTask() {
        final ActivityRecord opening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        opening.setOccludesParent(false);
        final ActivityRecord closing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        closing.setOccludesParent(false);
        final Task task = opening.getTask();
        mDisplayContent.mOpeningApps.add(opening);
        mDisplayContent.mClosingApps.add(closing);
        assertFalse(mAppTransitionController.isTransitWithinTask(TRANSIT_ACTIVITY_OPEN, task));
        closing.getTask().removeChild(closing);
        task.addChild(closing, 0);
        assertTrue(mAppTransitionController.isTransitWithinTask(TRANSIT_ACTIVITY_OPEN, task));
        assertFalse(mAppTransitionController.isTransitWithinTask(TRANSIT_TASK_OPEN, task));
    }
}
