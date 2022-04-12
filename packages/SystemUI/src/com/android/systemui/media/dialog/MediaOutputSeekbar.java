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

package com.android.systemui.media.dialog;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * Customized seekbar used by MediaOutputDialog, which only changes progress when dragging,
 * otherwise performs click.
 */
public class MediaOutputSeekbar extends SeekBar {
    private int mLastDownPosition = -1;

    public MediaOutputSeekbar(Context context) {
        super(context);
    }

    public MediaOutputSeekbar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mLastDownPosition = Math.round(event.getX());
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (mLastDownPosition == event.getX()) {
                performClick();
                return true;
            }
            mLastDownPosition = -1;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
