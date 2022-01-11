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

package android.inputmethodservice;

import static android.view.WindowManager.LayoutParams;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

import android.annotation.NonNull;
import android.content.Context;
import android.os.IBinder;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.policy.PhoneWindow;

/**
 * Window of type {@code LayoutParams.TYPE_INPUT_METHOD_DIALOG} for drawing
 * Handwriting Ink on screen.
 * @hide
 */
final class InkWindow extends PhoneWindow {

    private final WindowManager mWindowManager;

    public InkWindow(@NonNull Context context) {
        super(context);

        setType(LayoutParams.TYPE_INPUT_METHOD);
        final LayoutParams attrs = getAttributes();
        attrs.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setFitInsetsTypes(0);
        setAttributes(attrs);
        // Ink window is not touchable with finger.
        addFlags(FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_NOT_TOUCHABLE
                | FLAG_NOT_FOCUSABLE);
        setBackgroundDrawableResource(android.R.color.transparent);
        setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    /**
     * Method to show InkWindow on screen.
     * Emulates internal behavior similar to Dialog.show().
     */
    void show() {
        if (getDecorView() == null) {
            Slog.i(InputMethodService.TAG, "DecorView is not set for InkWindow. show() failed.");
            return;
        }
        getDecorView().setVisibility(View.VISIBLE);
        mWindowManager.addView(getDecorView(), getAttributes());
    }

    /**
     * Method to hide InkWindow from screen.
     * Emulates internal behavior similar to Dialog.hide().
     * @param remove set {@code true} to remove InkWindow surface completely.
     */
    void hide(boolean remove) {
        if (getDecorView() != null) {
            getDecorView().setVisibility(remove ? View.GONE : View.INVISIBLE);
        }
    }

    void setToken(@NonNull IBinder token) {
        WindowManager.LayoutParams lp = getAttributes();
        lp.token = token;
        setAttributes(lp);
    }
}
