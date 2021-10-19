/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.companion;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;


/**
 * Instrumentation test runner for Companion tests.
 */
public class CompanionTestRunner extends InstrumentationTestRunner {
    private static final String TAG = "CompanionTestRunner";

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(BluetoothDeviceFilterUtilsTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return CompanionTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
    }
}
