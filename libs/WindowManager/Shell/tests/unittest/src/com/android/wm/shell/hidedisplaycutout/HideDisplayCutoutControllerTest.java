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

package com.android.wm.shell.hidedisplaycutout;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.ShellExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HideDisplayCutoutControllerTest {
    private TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    private HideDisplayCutoutController mHideDisplayCutoutController;
    @Mock
    private HideDisplayCutoutOrganizer mMockDisplayAreaOrganizer;
    @Mock
    private ShellExecutor mMockMainExecutor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHideDisplayCutoutController = new HideDisplayCutoutController(
                mContext, mMockDisplayAreaOrganizer, mMockMainExecutor);
    }

    @Test
    public void testToggleHideDisplayCutout_On() {
        mHideDisplayCutoutController.mEnabled = false;
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_hideDisplayCutoutWithDisplayArea, true);
        reset(mMockDisplayAreaOrganizer);
        mHideDisplayCutoutController.updateStatus();
        verify(mMockDisplayAreaOrganizer).enableHideDisplayCutout();
    }

    @Test
    public void testToggleHideDisplayCutout_Off() {
        mHideDisplayCutoutController.mEnabled = true;
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_hideDisplayCutoutWithDisplayArea, false);
        mHideDisplayCutoutController.updateStatus();
        verify(mMockDisplayAreaOrganizer).disableHideDisplayCutout();
    }
}
