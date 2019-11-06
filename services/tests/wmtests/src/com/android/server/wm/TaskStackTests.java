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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link TaskStack} class.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskStackTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskStackTests extends WindowTestsBase {

    @Test
    public void testStackPositionChildAt() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 1 /* userId */);

        // Current user task should be moved to top.
        stack.positionChildAt(WindowContainer.POSITION_TOP, task1, false /* includingParents */);
        assertEquals(stack.mChildren.get(0), task2);
        assertEquals(stack.mChildren.get(1), task1);

        // Non-current user won't be moved to top.
        stack.positionChildAt(WindowContainer.POSITION_TOP, task2, false /* includingParents */);
        assertEquals(stack.mChildren.get(0), task2);
        assertEquals(stack.mChildren.get(1), task1);
    }

    @Test
    public void testClosingAppDifferentStackOrientation() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        ActivityRecord activity1 =
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        task1.addChild(activity1, 0);
        activity1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final Task task2 = createTaskInStack(stack, 1 /* userId */);
        ActivityRecord activity2=
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        task2.addChild(activity2, 0);
        activity2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, stack.getOrientation());
        mDisplayContent.mClosingApps.add(activity2);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, stack.getOrientation());
    }

    @Test
    public void testMoveTaskToBackDifferentStackOrientation() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack, 0 /* userId */);
        ActivityRecord activity1 =
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        task1.addChild(activity1, 0);
        activity1.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final Task task2 = createTaskInStack(stack, 1 /* userId */);
        ActivityRecord activity2 =
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        task2.addChild(activity2, 0);
        activity2.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, stack.getOrientation());
        task2.setSendingToBottom(true);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, stack.getOrientation());
    }

    @Test
    public void testStackRemoveImmediately() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        assertEquals(stack, task.getTaskStack());

        // Remove stack and check if its child is also removed.
        stack.removeImmediately();
        assertNull(stack.getDisplayContent());
        assertNull(task.getTaskStack());
    }

    @Test
    public void testRemoveContainer() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);

        assertNotNull(stack);
        assertNotNull(task);
        stack.removeIfPossible();
        // Assert that the container was removed.
        assertNull(stack.getParent());
        assertEquals(0, stack.getChildCount());
        assertNull(stack.getDisplayContent());
        assertNull(task.getDisplayContent());
        assertNull(task.getTaskStack());
    }

    @Test
    public void testRemoveContainer_deferRemoval() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);

        // Stack removal is deferred if one of its child is animating.
        doReturn(true).when(task).isAnimating(TRANSITION | CHILDREN);

        stack.removeIfPossible();
        // For the case of deferred removal the task controller will still be connected to the its
        // container until the stack window container is removed.
        assertNotNull(stack.getParent());
        assertNotEquals(0, stack.getChildCount());
        assertNotNull(task);

        stack.removeImmediately();
        // After removing, the task will be isolated.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
    }

    @Test
    public void testReparent() {
        // Create first stack on primary display.
        final TaskStack stack1 = createTaskStackOnDisplay(mDisplayContent);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);

        // Create second display and put second stack on it.
        final DisplayContent dc = createNewDisplay();
        final TaskStack stack2 = createTaskStackOnDisplay(dc);

        // Reparent
        stack1.reparent(dc.getDisplayId(), new Rect(), true /* onTop */);
        assertEquals(dc, stack1.getDisplayContent());
        final int stack1PositionInParent = stack1.getParent().mChildren.indexOf(stack1);
        final int stack2PositionInParent = stack1.getParent().mChildren.indexOf(stack2);
        assertEquals(stack1PositionInParent, stack2PositionInParent + 1);
        verify(task1, times(1)).onDisplayChanged(any());
    }

    @Test
    public void testStackOutset() {
        final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
        final int stackOutset = 10;
        spyOn(stack);
        doReturn(stackOutset).when(stack).getStackOutset();

        final Rect stackBounds = new Rect(200, 200, 800, 1000);
        // Update surface position and size by the given bounds.
        stack.setBounds(stackBounds);

        assertEquals(stackBounds.width() + 2 * stackOutset, stack.getLastSurfaceSize().x);
        assertEquals(stackBounds.height() + 2 * stackOutset, stack.getLastSurfaceSize().y);
        assertEquals(stackBounds.left - stackOutset, stack.getLastSurfacePosition().x);
        assertEquals(stackBounds.top - stackOutset, stack.getLastSurfacePosition().y);
    }
}
