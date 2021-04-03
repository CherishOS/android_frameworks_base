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

package com.android.systemui.screenshot;

import static com.google.common.util.concurrent.Futures.getUnchecked;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.lang.Math.abs;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.ScrollCaptureResponse;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for ScrollCaptureController which manages sequential image acquisition for long
 * screenshots.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScrollCaptureControllerTest extends SysuiTestCase {

    private static final ScrollCaptureResponse EMPTY_RESPONSE =
            new ScrollCaptureResponse.Builder().build();

    @Test
    public void testInfinite() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(Integer.MIN_VALUE, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -90, screenshot.getTop());
        assertEquals("bottom", 160, screenshot.getBottom());

        // Test that top portion is >= getTargetTopSizeRatio()
        // (Due to tileHeight, top will almost always be larger than the target)
        float topPortion = abs(screenshot.getTop()) / abs((float) screenshot.getBottom());
        if (topPortion < controller.getTargetTopSizeRatio()) {
            fail("expected top portion > "
                    + (controller.getTargetTopSizeRatio() * 100) + "%"
                    + " but was " + (topPortion * 100));
        }
    }

    @Test
    public void testLimitedBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(Integer.MIN_VALUE, 150)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -100, screenshot.getTop());
        assertEquals("bottom", 150, screenshot.getBottom());
    }

    @Test
    public void testLimitedTopAndBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-50, 150)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -50, screenshot.getTop());
        assertEquals("bottom", 150, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopInfiniteBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-10, Integer.MAX_VALUE)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -10, screenshot.getTop());
        assertEquals("bottom", 240, screenshot.getBottom());
    }

    @Test
    public void testVeryLimitedTopLimitedBottom() {
        ScrollCaptureController controller = new TestScenario()
                .withPageHeight(100)
                .withMaxPages(2.5f)
                .withTileHeight(10)
                .withAvailableRange(-10, 200)
                .createController(mContext);

        ScrollCaptureController.LongScreenshot screenshot =
                getUnchecked(controller.run(EMPTY_RESPONSE));

        assertEquals("top", -10, screenshot.getTop());
        assertEquals("bottom", 200, screenshot.getBottom());
    }

    /**
     * Build and configure a stubbed controller for each test case.
     */
    private static class TestScenario {
        private int mPageHeight = -1;
        private int mTileHeight = -1;
        private boolean mAvailableRangeSet;
        private int mAvailableTop;
        private int mAvailableBottom;
        private int mLocalVisibleTop;
        private int mLocalVisibleBottom = -1;
        private float mMaxPages = -1;

        TestScenario withPageHeight(int pageHeight) {
            if (pageHeight < 0) {
                throw new IllegalArgumentException("pageHeight must be positive");
            }
            mPageHeight = pageHeight;
            return this;
        }

        TestScenario withTileHeight(int tileHeight) {
            if (tileHeight < 0) {
                throw new IllegalArgumentException("tileHeight must be positive");
            }
            mTileHeight = tileHeight;
            return this;
        }

        TestScenario withAvailableRange(int top, int bottom) {
            mAvailableRangeSet = true;
            mAvailableTop = top;
            mAvailableBottom = bottom;
            return this;
        }

        TestScenario withMaxPages(float maxPages) {
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages must be positive");
            }
            mMaxPages = maxPages;
            return this;
        }

        TestScenario withPageVisibleRange(int top, int bottom) {
            if (top < 0 || bottom < 0) {
                throw new IllegalArgumentException("top and bottom must be positive");
            }
            mLocalVisibleTop = top;
            mLocalVisibleBottom = bottom;
            return this;
        }


        ScrollCaptureController createController(Context context) {
            if (mTileHeight < 0) {
                throw new IllegalArgumentException("tileHeight not set");
            }
            if (!mAvailableRangeSet) {
                throw new IllegalArgumentException("availableRange not set");
            }
            if (mPageHeight < 0) {
                throw new IllegalArgumentException("pageHeight not set");
            }

            if (mMaxPages < 0) {
                throw new IllegalArgumentException("maxPages not set");
            }
            // Default: page fully visible
            if (mLocalVisibleBottom < 0) {
                mLocalVisibleBottom = mPageHeight;
            }
            Session session = new FakeSession(mPageHeight, mMaxPages, mTileHeight,
                    mLocalVisibleTop, mLocalVisibleBottom, mAvailableTop, mAvailableBottom);
            ScrollCaptureClient client = mock(ScrollCaptureClient.class);
            when(client.start(/* response */ any(), /* maxPages */ anyFloat()))
                    .thenReturn(immediateFuture(session));
            return new ScrollCaptureController(context, context.getMainExecutor(),
                    client, new ImageTileSet(context.getMainThreadHandler()));
        }
    }
}
