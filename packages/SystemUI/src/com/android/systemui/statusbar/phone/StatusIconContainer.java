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

/**
 * A container for Status bar system icons. Limits the number of system icons and handles overflow
 * similar to NotificationIconController. Can be used to layout nested StatusIconContainers
 *
 * Children are expected to be of type StatusBarIconView.
 */
package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.content.Context;
import android.util.ArrayMap;
import android.util.AttributeSet;

import android.view.View;
import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.stack.ViewState;

public class StatusIconContainer extends AlphaOptimizedLinearLayout {

    private static final String TAG = "StatusIconContainer";
    private static final int MAX_ICONS = 5;
    private static final int MAX_DOTS = 3;

    public StatusIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float midY = getHeight() / 2.0f;

        // Layout all child views so that we can move them around later
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (midY - height / 2.0f);
            child.layout(0, top, width, top + height);
        }

        resetViewStates();
        calculateIconTranslations();
        applyIconStates();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int count = getChildCount();
        // Measure all children so that they report the correct width
        for (int i = 0; i < count; i++) {
            measureChild(getChildAt(i), widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        ViewState vs = new ViewState();
        child.setTag(R.id.status_bar_view_state_tag, vs);
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        child.setTag(R.id.status_bar_view_state_tag, null);
    }

    /**
     * Layout is happening from end -> start
     */
    private void calculateIconTranslations() {
        float translationX = getWidth();
        float contentStart = getPaddingStart();
        int childCount = getChildCount();
        // Underflow === don't show content until that index
        int firstUnderflowIndex = -1;
        android.util.Log.d(TAG, "calculateIconTransitions: start=" + translationX);

        //TODO: Dots
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof StatusBarIconView)) {
                continue;
            }

            ViewState childState = getViewStateFromChild(child);
            if (childState == null ) {
                continue;
            }

            // Rely on StatusBarIcon for truth about visibility
            if (!((StatusBarIconView) child).getStatusBarIcon().visible) {
                childState.hidden = true;
                continue;
            }

            childState.xTranslation = translationX - child.getWidth();

            if (childState.xTranslation < contentStart) {
                if (firstUnderflowIndex == -1) {
                    firstUnderflowIndex = i;
                }
            }

            translationX -= child.getWidth();
        }

        if (firstUnderflowIndex != -1) {
            for (int i = 0; i <= firstUnderflowIndex; i++) {
                View child = getChildAt(i);
                ViewState vs = getViewStateFromChild(child);
                if (vs != null) {
                    vs.hidden = true;
                }
            }
        }
    }

    private void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState vs = getViewStateFromChild(child);
            if (vs != null) {
                vs.applyToView(child);
            }
        }
    }

    private void resetViewStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState vs = getViewStateFromChild(child);
            if (vs == null) {
                continue;
            }

            vs.initFrom(child);
            vs.alpha = 1.0f;
            if (child instanceof StatusBarIconView) {
                vs.hidden = !((StatusBarIconView)child).getStatusBarIcon().visible;
            } else {
                vs.hidden = false;
            }
        }
    }

    private static @Nullable ViewState getViewStateFromChild(View child) {
        return (ViewState) child.getTag(R.id.status_bar_view_state_tag);
    }
}
