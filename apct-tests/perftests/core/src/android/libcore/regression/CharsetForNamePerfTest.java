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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class CharsetForNamePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(name = "mCharsetName({0})")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {"UTF-16"},
                    {"UTF-8"},
                    {"UTF8"},
                    {"ISO-8859-1"},
                    {"8859_1"},
                    {"ISO-8859-2"},
                    {"8859_2"},
                    {"US-ASCII"},
                    {"ASCII"},
                });
    }

    @Parameterized.Parameter(0)
    public String mCharsetName;

    @Test
    public void timeCharsetForName() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Charset.forName(mCharsetName);
        }
    }
}
