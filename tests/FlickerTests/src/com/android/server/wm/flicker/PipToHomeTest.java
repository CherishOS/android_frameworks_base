/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm.flicker;

import static com.android.server.wm.flicker.CommonTransitions.exitPipModeToHome;

import androidx.test.filters.LargeTest;

import com.android.server.wm.flicker.helpers.PipAppHelper;

import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;

/**
 * Test Pip launch.
 * To run this test: {@code atest FlickerTests:PipToHomeTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PipToHomeTest extends PipTestBase {
    public PipToHomeTest(String beginRotationName, int beginRotation) {
        super(beginRotationName, beginRotation);
    }

    @Override
    TransitionRunner getTransitionToRun() {
        return exitPipModeToHome((PipAppHelper) mTestApp, mUiDevice, mBeginRotation)
                .includeJankyRuns().build();
    }

    @Ignore
    @Test
    public void checkVisibility_backgroundWindowVisibleBehindPipLayer() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAppWindowOnTop(sPipWindowTitle)
                .then()
                .showsBelowAppWindow("Wallpaper")
                .then()
                .showsAppWindowOnTop("Wallpaper")
                .forAllEntries());
    }
}
