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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;

/**
 * The view in the statusBar that contains part of the heads-up information
 */
public class HeadsUpStatusBarView extends AlphaOptimizedLinearLayout {
    private int mAbsoluteStartPadding;
    private int mEndMargin;
    private View mIconPlaceholder;
    private TextView mTextView;
    private NotificationData.Entry mShowingEntry;
    private Rect mLayoutedIconRect = new Rect();
    private int[] mTmpPosition = new int[2];
    private boolean mFirstLayout = true;
    private boolean mPublicMode;
    private int mMaxWidth;
    private View mRootView;
    private int mSysWinInset;
    private int mCutOutInset;
    private Rect mIconDrawingRect = new Rect();
    private Point mPoint;
    private Runnable mOnDrawingRectChangedListener;

    public HeadsUpStatusBarView(Context context) {
        this(context, null);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = getResources();
        mAbsoluteStartPadding = res.getDimensionPixelSize(R.dimen.notification_side_paddings)
            + res.getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin_start);
        mEndMargin = res.getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        setPaddingRelative(mAbsoluteStartPadding, 0, mEndMargin, 0);
        updateMaxWidth();
    }

    private void updateMaxWidth() {
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.qs_panel_width);
        if (maxWidth != mMaxWidth) {
            // maxWidth doesn't work with fill_parent, let's manually make it at most as big as the
            // notification panel
            mMaxWidth = maxWidth;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxWidth > 0) {
            int newSize = Math.min(MeasureSpec.getSize(widthMeasureSpec), mMaxWidth);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(newSize,
                    MeasureSpec.getMode(widthMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateMaxWidth();
    }

    @VisibleForTesting
    public HeadsUpStatusBarView(Context context, View iconPlaceholder, TextView textView) {
        this(context);
        mIconPlaceholder = iconPlaceholder;
        mTextView = textView;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconPlaceholder = findViewById(R.id.icon_placeholder);
        mTextView = findViewById(R.id.text);
    }

    public void setEntry(NotificationData.Entry entry) {
        if (entry != null) {
            mShowingEntry = entry;
            CharSequence text = entry.headsUpStatusBarText;
            if (mPublicMode) {
                text = entry.headsUpStatusBarTextPublic;
            }
            mTextView.setText(text);
        } else {
            mShowingEntry = null;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mIconPlaceholder.getLocationOnScreen(mTmpPosition);
        int left = (int) (mTmpPosition[0] - getTranslationX());
        int top = mTmpPosition[1];
        int right = left + mIconPlaceholder.getWidth();
        int bottom = top + mIconPlaceholder.getHeight();
        mLayoutedIconRect.set(left, top, right, bottom);
        updateDrawingRect();
        int targetPadding = mAbsoluteStartPadding + mSysWinInset + mCutOutInset;
        if (left != targetPadding) {
            int start;
            if (isLayoutRtl()) {
                if (mPoint == null) {
                    mPoint = new Point();
                }
                getDisplay().getRealSize(mPoint);
                start = (mPoint.x - right);
            } else {
                start = left;
            }

            int newPadding = targetPadding - start + getPaddingStart();
            setPaddingRelative(newPadding, 0, mEndMargin, 0);
        }
        if (mFirstLayout) {
            // we need to do the padding calculation in the first frame, so the layout specified
            // our visibility to be INVISIBLE in the beginning. let's correct that and set it
            // to GONE.
            setVisibility(GONE);
            mFirstLayout = false;
        }
    }

    public void setPanelTranslation(float translationX) {
        if (isLayoutRtl()) {
            setTranslationX(translationX + mCutOutInset);
        } else {
            setTranslationX(translationX - mCutOutInset);
        }
        updateDrawingRect();
    }

    private void updateDrawingRect() {
        float oldLeft = mIconDrawingRect.left;
        mIconDrawingRect.set(mLayoutedIconRect);
        mIconDrawingRect.offset((int) getTranslationX(), 0);
        if (oldLeft != mIconDrawingRect.left && mOnDrawingRectChangedListener != null) {
            mOnDrawingRectChangedListener.run();
        }
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        boolean isRtl = isLayoutRtl();
        mSysWinInset = isRtl ? insets.right : insets.left;
        DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
        mCutOutInset = (displayCutout != null)
                ? (isRtl ? displayCutout.getSafeInsetRight() : displayCutout.getSafeInsetLeft())
                : 0;
        // For Double Cut Out mode, the System window navigation bar is at the right
        // side of the left cut out. In this condition, mSysWinInset include the left cut
        // out width so we set mCutOutInset to be 0. For RTL, the condition is the same.
        // The navigation bar is at the left side of the right cut out and include the
        // right cut out width.
        if (mSysWinInset != 0) {
            mCutOutInset = 0;
        }

        return super.fitSystemWindows(insets);
    }

    public NotificationData.Entry getShowingEntry() {
        return mShowingEntry;
    }

    public Rect getIconDrawingRect() {
        return mIconDrawingRect;
    }

    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTextView.setTextColor(DarkIconDispatcher.getTint(area, this, tint));
    }

    public void setPublicMode(boolean publicMode) {
        mPublicMode = publicMode;
    }

    public void setOnDrawingRectChangedListener(Runnable onDrawingRectChangedListener) {
        mOnDrawingRectChangedListener = onDrawingRectChangedListener;
    }
}
