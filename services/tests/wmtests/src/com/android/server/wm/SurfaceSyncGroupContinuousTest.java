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

package com.android.server.wm;

import static android.server.wm.UiDeviceUtils.pressUnlockButton;
import static android.server.wm.UiDeviceUtils.pressWakeupButton;
import static android.server.wm.WindowManagerState.getLogicalDisplaySize;

import android.app.KeyguardManager;
import android.os.PowerManager;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.cts.surfacevalidator.CapturedActivity;
import android.window.SurfaceSyncGroup;

import androidx.test.rule.ActivityTestRule;

import com.android.server.wm.scvh.SyncValidatorSCVHTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.Objects;

public class SurfaceSyncGroupContinuousTest {
    @Rule
    public TestName mName = new TestName();

    @Rule
    public ActivityTestRule<CapturedActivity> mActivityRule =
            new ActivityTestRule<>(CapturedActivity.class);

    public CapturedActivity mCapturedActivity;

    @Before
    public void setup() {
        mCapturedActivity = mActivityRule.getActivity();
        mCapturedActivity.setLogicalDisplaySize(getLogicalDisplaySize());

        final KeyguardManager km = mCapturedActivity.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked() || !Objects.requireNonNull(
                mCapturedActivity.getSystemService(PowerManager.class)).isInteractive()) {
            pressWakeupButton();
            pressUnlockButton();
        }
        SurfaceSyncGroup.setTransactionFactory(SurfaceControl.Transaction::new);
    }

    @Test
    public void testSurfaceViewSyncDuringResize() throws Throwable {
        mCapturedActivity.verifyTest(new SurfaceSyncGroupValidatorTestCase(), mName);
    }

    @Test
    public void testSurfaceControlViewHostIPCSync_Fast() throws Throwable {
        mCapturedActivity.verifyTest(
                new SyncValidatorSCVHTestCase(0 /* delayMs */, false /* overrideDefaultDuration */),
                mName);
    }

    @Test
    public void testSurfaceControlViewHostIPCSync_Slow() throws Throwable {
        mCapturedActivity.verifyTest(new SyncValidatorSCVHTestCase(100 /* delayMs */,
                false /* overrideDefaultDuration */), mName);
    }

    @Test
    @Presubmit
    public void testSurfaceControlViewHostIPCSync_Short() throws Throwable {
        mCapturedActivity.setMinimumCaptureDurationMs(5000);
        mCapturedActivity.verifyTest(
                new SyncValidatorSCVHTestCase(0 /* delayMs */, true /* overrideDefaultDuration */),
                mName);
    }
}
