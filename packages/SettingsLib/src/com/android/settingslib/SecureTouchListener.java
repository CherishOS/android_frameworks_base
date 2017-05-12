/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

/**
 * A touch listener which consumes touches when another window is partly or wholly obscuring the
 * window containing the view this listener is attached to.
 * Optionally accepts a string to show the user as a toast when consuming an insecure touch
 */
public class SecureTouchListener implements View.OnTouchListener {

    private static final long TAP_DEBOUNCE_TIME = 2000;
    private long mLastToastTime = 0;
    private String mWarningText;

    public SecureTouchListener() {
        this(null);
    }

    public SecureTouchListener(String warningText) {
        mWarningText = warningText;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if ((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
                || (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) {
            if (mWarningText != null) {
                // Show a toast warning the user
                final long currentTime = SystemClock.uptimeMillis();
                if (currentTime - mLastToastTime > TAP_DEBOUNCE_TIME) {
                    mLastToastTime = currentTime;
                    Toast.makeText(v.getContext(), mWarningText, Toast.LENGTH_SHORT).show();
                }
            }
            // Consume the touch event
            return true;
        }
        return false;
    }
}
