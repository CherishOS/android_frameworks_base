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

package com.android.server.utils;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import android.util.Log;
import android.util.Slog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Run it as {@code atest FrameworksMockingServicesTests:SlogfTest}
 */
public final class SlogfTest {

    private static final String TAG = SlogfTest.class.getSimpleName();

    private MockitoSession mSession;

    private final Throwable mThrowable = new Throwable("D'OH!");

    @Before
    public void setup() {
        mSession = mockitoSession()
                .initMocks(this)
                .mockStatic(Slog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
    }

    @After
    public void tearDown() {
        if (mSession == null) {
            Log.w(TAG, "finishSession(): no session");
        } else {
            mSession.finishMocking();
        }
    }

    @Test
    public void testV_msg() {
        Slogf.v(TAG, "msg");

        verify(()-> Slog.v(TAG, "msg"));
    }

    @Test
    public void testV_msgAndThrowable() {
        Slogf.v(TAG, "msg", mThrowable);

        verify(()-> Slog.v(TAG, "msg", mThrowable));
    }

    @Test
    public void testV_msgFormatted() {
        Slogf.v(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.v(TAG, "msg in a bottle"));
    }

    @Test
    public void testV_msgFormattedWithThrowable() {
        Slogf.v(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.v(TAG, "msg in a bottle", mThrowable));
    }

    @Test
    public void testD_msg() {
        Slogf.d(TAG, "msg");

        verify(()-> Slog.d(TAG, "msg"));
    }

    @Test
    public void testD_msgAndThrowable() {
        Slogf.d(TAG, "msg", mThrowable);

        verify(()-> Slog.d(TAG, "msg", mThrowable));
    }

    @Test
    public void testD_msgFormatted() {
        Slogf.d(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.d(TAG, "msg in a bottle"));
    }

    @Test
    public void testD_msgFormattedWithThrowable() {
        Slogf.d(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.d(TAG, "msg in a bottle", mThrowable));
    }

    @Test
    public void testI_msg() {
        Slogf.i(TAG, "msg");

        verify(()-> Slog.i(TAG, "msg"));
    }

    @Test
    public void testI_msgAndThrowable() {
        Slogf.i(TAG, "msg", mThrowable);

        verify(()-> Slog.i(TAG, "msg", mThrowable));
    }

    @Test
    public void testI_msgFormatted() {
        Slogf.i(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.i(TAG, "msg in a bottle"));
    }

    @Test
    public void testI_msgFormattedWithThrowable() {
        Slogf.i(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.i(TAG, "msg in a bottle", mThrowable));
    }

    @Test
    public void testW_msg() {
        Slogf.w(TAG, "msg");

        verify(()-> Slog.w(TAG, "msg"));
    }

    @Test
    public void testW_msgAndThrowable() {
        Slogf.w(TAG, "msg", mThrowable);

        verify(()-> Slog.w(TAG, "msg", mThrowable));
    }

    @Test
    public void testW_Throwable() {
        Slogf.w(TAG, mThrowable);

        verify(()-> Slog.w(TAG, mThrowable));
    }

    @Test
    public void testW_msgFormatted() {
        Slogf.w(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.w(TAG, "msg in a bottle"));
    }

    @Test
    public void testW_msgFormattedWithThrowable() {
        Slogf.w(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.w(TAG, "msg in a bottle", mThrowable));
    }

    @Test
    public void testE_msg() {
        Slogf.e(TAG, "msg");

        verify(()-> Slog.e(TAG, "msg"));
    }

    @Test
    public void testE_msgAndThrowable() {
        Slogf.e(TAG, "msg", mThrowable);

        verify(()-> Slog.e(TAG, "msg", mThrowable));
    }

    @Test
    public void testE_msgFormatted() {
        Slogf.e(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.e(TAG, "msg in a bottle"));
    }

    @Test
    public void testE_msgFormattedWithThrowable() {
        Slogf.e(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.e(TAG, "msg in a bottle", mThrowable));
    }

    @Test
    public void testWtf_msg() {
        Slogf.wtf(TAG, "msg");

        verify(()-> Slog.wtf(TAG, "msg"));
    }

    @Test
    public void testWtf_msgAndThrowable() {
        Slogf.wtf(TAG, "msg", mThrowable);

        verify(()-> Slog.wtf(TAG, "msg", mThrowable));
    }

    @Test
    public void testWtf_Throwable() {
        Slogf.wtf(TAG, mThrowable);

        verify(()-> Slog.wtf(TAG, mThrowable));
    }

    @Test
    public void testWtf_msgFormatted() {
        Slogf.wtf(TAG, "msg in a %s", "bottle");

        verify(()-> Slog.wtf(TAG, "msg in a bottle"));
    }

    @Test
    public void testWtfQuiet() {
        Slogf.wtfQuiet(TAG, "msg");

        verify(()-> Slog.wtfQuiet(TAG, "msg"));
    }

    @Test
    public void testWtfStack() {
        Slogf.wtfStack(TAG, "msg");

        verify(()-> Slog.wtfStack(TAG, "msg"));
    }

    @Test
    public void testPrintln() {
        Slogf.println(42, TAG, "msg");

        verify(()-> Slog.println(42, TAG, "msg"));
    }

    @Test
    public void testWtf_msgFormattedWithThrowable() {
        Slogf.wtf(TAG, mThrowable, "msg in a %s", "bottle");

        verify(()-> Slog.wtf(TAG, "msg in a bottle", mThrowable));
    }
}
