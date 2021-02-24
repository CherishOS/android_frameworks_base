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

package com.android.server.wm.flicker.ime

import android.platform.helpers.IAppHelper
import com.android.server.wm.flicker.FlickerTestParameter

const val IME_WINDOW_TITLE = "InputMethod"

fun FlickerTestParameter.imeLayerBecomesVisible() {
    assertLayers {
        this.hidesLayer(IME_WINDOW_TITLE)
            .then()
            .showsLayer(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeLayerBecomesInvisible() {
    assertLayers {
        this.showsLayer(IME_WINDOW_TITLE)
            .then()
            .hidesLayer(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeAppLayerIsAlwaysVisible(testApp: IAppHelper) {
    assertLayers {
        this.showsLayer(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeAppWindowIsAlwaysVisible(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeWindowBecomesVisible() {
    assertWm {
        this.hidesNonAppWindow(IME_WINDOW_TITLE)
            .then()
            .showsNonAppWindow(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeWindowBecomesInvisible() {
    assertWm {
        this.showsNonAppWindow(IME_WINDOW_TITLE)
            .then()
            .hidesNonAppWindow(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeAppWindowBecomesVisible(windowName: String) {
    assertWm {
        this.hidesAppWindow(windowName)
            .then()
            .showsAppWindow(windowName)
    }
}

fun FlickerTestParameter.imeAppWindowBecomesInvisible(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
            .then()
            .appWindowNotOnTop(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeAppLayerBecomesInvisible(testApp: IAppHelper) {
    assertLayers {
        this.skipUntilFirstAssertion()
            .showsLayer(testApp.getPackage())
            .then()
            .hidesLayer(testApp.getPackage())
    }
}