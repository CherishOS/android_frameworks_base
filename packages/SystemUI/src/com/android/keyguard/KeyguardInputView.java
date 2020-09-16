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

package com.android.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * A Base class for all Keyguard password/pattern/pin related inputs.
 */
public abstract class KeyguardInputView extends LinearLayout {

    public KeyguardInputView(Context context) {
        super(context);
    }

    public KeyguardInputView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyguardInputView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    abstract CharSequence getTitle();

    boolean disallowInterceptTouch(MotionEvent event) {
        return false;
    }

    void startAppearAnimation() {}

    boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }
}
