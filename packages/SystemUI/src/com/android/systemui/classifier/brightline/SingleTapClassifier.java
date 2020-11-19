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

package com.android.systemui.classifier.brightline;

import android.view.MotionEvent;

import java.util.List;

/**
 * Falsing classifier that accepts or rejects a single gesture as a tap.
 */
public class SingleTapClassifier extends FalsingClassifier {
    private final float mTouchSlop;
    private String mReason;

    SingleTapClassifier(FalsingDataProvider dataProvider, float touchSlop) {
        super(dataProvider);
        mTouchSlop = touchSlop;
    }

    @Override
    boolean isFalseTouch() {
        return !isTap(getRecentMotionEvents());
    }

    /** Given a list of {@link android.view.MotionEvent}'s, returns true if the look like a tap. */
    public boolean isTap(List<MotionEvent> motionEvents) {
        float downX = motionEvents.get(0).getX();
        float downY = motionEvents.get(0).getY();

        for (MotionEvent event : motionEvents) {
            if (Math.abs(event.getX() - downX) >= mTouchSlop) {
                mReason = "dX too big for a tap: "
                        + Math.abs(event.getX() - downX)
                        + "vs "
                        + mTouchSlop;
                return false;
            } else if (Math.abs(event.getY() - downY) >= mTouchSlop) {
                mReason = "dY too big for a tap: "
                        + Math.abs(event.getY() - downY)
                        + "vs "
                        + mTouchSlop;
                return false;
            }
        }
        mReason = "";
        return true;
    }

    @Override
    String getReason() {
        return mReason;
    }
}
