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

package com.android.server.wm.flicker.helpers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.app.Instrumentation;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

public class ImeAppHelper extends FlickerAppHelper {

    ImeAppHelper(Instrumentation instr, String launcherName) {
        super(instr, launcherName);
    }

    public ImeAppHelper(Instrumentation instr) {
        this(instr, "ImeApp");
    }

    public void openIME(UiDevice device) {
        UiObject2 editText = device.wait(
                Until.findObject(By.res(getPackage(), "plain_text_input")),
                AutomationUtils.FIND_TIMEOUT);
        assertNotNull("Text field not found, this usually happens when the device was left "
                + "in an unknown state (e.g. in split screen)", editText);
        editText.click();

        if (!AutomationUtils.waitForIME(device)) {
            fail("IME did not appear");
        }
    }
}
