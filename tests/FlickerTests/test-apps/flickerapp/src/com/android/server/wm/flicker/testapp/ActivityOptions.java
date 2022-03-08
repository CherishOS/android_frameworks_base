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

package com.android.server.wm.flicker.testapp;

import android.content.ComponentName;

public class ActivityOptions {
    public static final String EXTRA_STARVE_UI_THREAD = "StarveUiThread";
    public static final String FLICKER_APP_PACKAGE = "com.android.server.wm.flicker.testapp";

    public static final String SEAMLESS_ACTIVITY_LAUNCHER_NAME = "SeamlessApp";
    public static final ComponentName SEAMLESS_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".SeamlessRotationActivity");

    public static final String IME_ACTIVITY_AUTO_FOCUS_LAUNCHER_NAME = "ImeAppAutoFocus";
    public static final ComponentName IME_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeActivityAutoFocus");

    public static final String IME_ACTIVITY_LAUNCHER_NAME = "ImeActivity";
    public static final ComponentName IME_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeActivity");

    public static final String SIMPLE_ACTIVITY_LAUNCHER_NAME = "SimpleApp";
    public static final ComponentName SIMPLE_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".SimpleActivity");

    public static final String NON_RESIZEABLE_ACTIVITY_LAUNCHER_NAME = "NonResizeableApp";
    public static final ComponentName NON_RESIZEABLE_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".NonResizeableActivity");

    public static final String BUTTON_ACTIVITY_LAUNCHER_NAME = "ButtonApp";
    public static final ComponentName BUTTON_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ButtonActivity");

    public static final String LAUNCH_NEW_TASK_ACTIVITY_LAUNCHER_NAME = "LaunchNewTaskApp";
    public static final ComponentName LAUNCH_NEW_TASK_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".LaunchNewTaskActivity");
}
