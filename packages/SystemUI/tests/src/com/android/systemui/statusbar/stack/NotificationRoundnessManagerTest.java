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
 * limitations under the License
 */

package com.android.systemui.statusbar.stack;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRoundnessManagerTest extends SysuiTestCase {

    private NotificationRoundnessManager mRoundnessManager = new NotificationRoundnessManager();
    private HashSet<View> mAnimatedChildren = new HashSet<>();
    private Runnable mRoundnessCallback = mock(Runnable.class);
    private ExpandableNotificationRow mFirst;
    private ExpandableNotificationRow mSecond;

    @Before
    public void setUp() throws Exception {
        NotificationTestHelper testHelper = new NotificationTestHelper(getContext());
        mFirst = testHelper.createRow();
        mSecond = testHelper.createRow();
        mRoundnessManager.setOnRoundingChangedCallback(mRoundnessCallback);
        mRoundnessManager.setAnimatedChildren(mAnimatedChildren);
        mRoundnessManager.setFirstAndLastBackgroundChild(mFirst, mFirst);
        mRoundnessManager.setExpanded(1.0f, 1.0f);
    }

    @Test
    public void testCallbackCalledWhenSecondChanged() {
        mRoundnessManager.setFirstAndLastBackgroundChild(mFirst, mSecond);
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testCallbackCalledWhenFirstChanged() {
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mFirst);
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testRoundnessSetOnLast() {
        mRoundnessManager.setFirstAndLastBackgroundChild(mFirst, mSecond);
        Assert.assertEquals(1.0f, mSecond.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(0.0f, mSecond.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundnessSetOnNew() {
        mRoundnessManager.setFirstAndLastBackgroundChild(mFirst, null);
        Assert.assertEquals(0.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testCompleteReplacement() {
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(0.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(0.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testNotCalledWhenRemoved() {
        mFirst.setRemoved();
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(1.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedWhenPinnedAndCollapsed() {
        mFirst.setPinned(true);
        mRoundnessManager.setExpanded(0.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(1.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedWhenGoingAwayAndCollapsed() {
        mFirst.setHeadsUpAnimatingAway(true);
        mRoundnessManager.setExpanded(0.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(1.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedNormalRoundingWhenExpanded() {
        mFirst.setHeadsUpAnimatingAway(true);
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(0.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(0.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testTrackingHeadsUpRoundedIfPushingUp() {
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, -0.5f /* appearFraction */);
        mRoundnessManager.setTrackingHeadsUp(mFirst);
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(1.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }

    @Test
    public void testTrackingHeadsUpNotRoundedIfPushingDown() {
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, 0.5f /* appearFraction */);
        mRoundnessManager.setTrackingHeadsUp(mFirst);
        mRoundnessManager.setFirstAndLastBackgroundChild(mSecond, mSecond);
        Assert.assertEquals(0.0f, mFirst.getCurrentBottomRoundness(), 0.0f);
        Assert.assertEquals(0.0f, mFirst.getCurrentTopRoundness(), 0.0f);
    }
}
