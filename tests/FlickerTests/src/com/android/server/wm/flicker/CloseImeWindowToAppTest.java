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

package com.android.server.wm.flicker;

import static com.android.server.wm.flicker.CommonTransitions.editTextLoseFocusToApp;
import static com.android.server.wm.flicker.WindowUtils.getDisplayBounds;

import android.platform.helpers.IAppHelper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test IME window closing back to app window transitions.
 * To run this test: {@code atest FlickerTests:CloseImeWindowToAppTest}
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class CloseImeWindowToAppTest extends FlickerTestBase {

    private static final String IME_WINDOW_TITLE = "InputMethod";
    private IAppHelper mImeTestApp = new StandardAppHelper(
            InstrumentationRegistry.getInstrumentation(),
            "com.android.server.wm.flicker.testapp", "ImeApp");

    @Before
    public void runTransition() {
        super.runTransition(editTextLoseFocusToApp(uiDevice)
                .includeJankyRuns().build());
    }

    @Test
    public void checkVisibility_imeLayerBecomesInvisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(IME_WINDOW_TITLE)
                .then()
                .hidesLayer(IME_WINDOW_TITLE)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_imeAppLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(mImeTestApp.getPackage())
                .forAllEntries());
    }

    @Test
    public void checkVisibility_imeAppWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAppWindowOnTop(mImeTestApp.getPackage())
                .forAllEntries());
    }

    @Test
    public void checkCoveredRegion_noUncoveredRegions() {
        checkResults(result -> LayersTraceSubject.assertThat(result).coversRegion(
                getDisplayBounds()).forAllEntries());
    }
}
