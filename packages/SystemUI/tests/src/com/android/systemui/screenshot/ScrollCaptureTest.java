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

package com.android.systemui.screenshot;

import static org.junit.Assert.fail;

import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.util.Log;
import android.view.Display;
import android.view.IScrollCaptureClient;
import android.view.IScrollCaptureController;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests the of internal framework Scroll Capture API from SystemUI.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScrollCaptureTest extends SysuiTestCase {
    private static final String TAG = "ScrollCaptureTest";

    /**
     * Verifies that a request traverses from SystemUI, to WindowManager and to the app process and
     * is returned without error. Device must be unlocked.
     */
    @Test
    public void testBasicOperation() throws InterruptedException {
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();

        // Start an activity to be on top that will be targeted
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(mContext, ScrollViewActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK));

        final CountDownLatch latch = new CountDownLatch(1);
        try {
            wms.requestScrollCapture(Display.DEFAULT_DISPLAY, null, -1,
                    new IScrollCaptureController.Stub() {
                        @Override
                        public void onClientConnected(
                                IScrollCaptureClient client, Rect scrollBounds,
                                Point positionInWindow) {
                            Log.d(TAG,
                                    "client connected: " + client + "[scrollBounds= " + scrollBounds
                                            + ", positionInWindow=" + positionInWindow + "]");
                            latch.countDown();
                        }

                        @Override
                        public void onClientUnavailable() {
                        }

                        @Override
                        public void onCaptureStarted() {
                        }

                        @Override
                        public void onCaptureBufferSent(long frameNumber, Rect capturedArea) {
                        }

                        @Override
                        public void onConnectionClosed() {
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "request failed", e);
            fail("caught remote exception " + e);
        }
        latch.await(1000, TimeUnit.MILLISECONDS);
    }
}
