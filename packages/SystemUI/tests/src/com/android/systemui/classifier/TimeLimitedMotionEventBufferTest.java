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

package com.android.systemui.classifier;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class TimeLimitedMotionEventBufferTest extends SysuiTestCase {

    private static final long MAX_AGE_MS = 100;

    private TimeLimitedMotionEventBuffer mBuffer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mBuffer = new TimeLimitedMotionEventBuffer(MAX_AGE_MS);
    }

    @After
    public void tearDown() {
        for (MotionEvent motionEvent : mBuffer) {
            motionEvent.recycle();
        }
        mBuffer.clear();
    }

    @Test
    public void testAllEventsRetained() {
        MotionEvent eventA = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent eventB = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 0, 0, 0);
        MotionEvent eventC = MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 0, 0, 0);
        MotionEvent eventD = MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 0, 0, 0);

        mBuffer.add(eventA);
        mBuffer.add(eventB);
        mBuffer.add(eventC);
        mBuffer.add(eventD);

        assertThat(mBuffer.size(), is(4));
    }

    @Test
    public void testOlderEventsRemoved() {
        MotionEvent eventA = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent eventB = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 0, 0, 0);
        MotionEvent eventC = MotionEvent.obtain(
                0, MAX_AGE_MS + 1, MotionEvent.ACTION_MOVE, 0, 0, 0);
        MotionEvent eventD = MotionEvent.obtain(
                0, MAX_AGE_MS + 2, MotionEvent.ACTION_UP, 0, 0, 0);

        mBuffer.add(eventA);
        mBuffer.add(eventB);
        assertThat(mBuffer.size(), is(2));

        mBuffer.add(eventC);
        mBuffer.add(eventD);
        assertThat(mBuffer.size(), is(2));

        assertThat(mBuffer.get(0), is(eventC));
        assertThat(mBuffer.get(1), is(eventD));
    }
}
