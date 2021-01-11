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

package com.android.wm.shell;

import android.util.Slog;

import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * An entry point into the shell for dumping shell internal state and running adb commands.
 *
 * Use with {@code adb shell dumpsys activity service SystemUIService WMShell ...}.
 */
public interface ShellCommandHandler {
    /**
     * Dumps the shell state.
     */
    void dump(PrintWriter pw);

    /**
     * Handles a shell command.
     */
    boolean handleCommand(final String[] args, PrintWriter pw);
}
