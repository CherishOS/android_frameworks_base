/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.wm.shell.animation.PhysicsAnimator;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link QuickStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout {

    private final Point mSizePoint = new Point();
    private static final FloatPropertyCompat<QSContainerImpl> BACKGROUND_BOTTOM =
            new FloatPropertyCompat<QSContainerImpl>("backgroundBottom") {
                @Override
                public float getValue(QSContainerImpl qsImpl) {
                    return qsImpl.getBackgroundBottom();
                }

                @Override
                public void setValue(QSContainerImpl background, float value) {
                    background.setBackgroundBottom((int) value);
                }
            };
    private static final PhysicsAnimator.SpringConfig BACKGROUND_SPRING
            = new PhysicsAnimator.SpringConfig(SpringForce.STIFFNESS_MEDIUM,
            SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    private int mBackgroundBottom = -1;
    private int mHeightOverride = -1;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private NonInterceptingScrollView mQSPanelContainer;

    private View mBackground;

    private int mSideMargins;
    private boolean mQsDisabled;
    private boolean mBackgroundVisible;
    private int mContentPadding = -1;
    private boolean mAnimateBottomOnNextLayout;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanelContainer = findViewById(R.id.expanded_qs_scroll_view);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mBackground = findViewById(R.id.quick_settings_background);
        mHeader.getHeaderQsPanel().setMediaVisibilityChangedListener((visible) -> {
            if (mHeader.getHeaderQsPanel().isShown()) {
                mAnimateBottomOnNextLayout = true;
            }
        });
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    void onMediaVisibilityChanged(boolean qsVisible) {
        mAnimateBottomOnNextLayout = qsVisible;
    }

    private void setBackgroundBottom(int value) {
        // We're saving the bottom separately since otherwise the bottom would be overridden in
        // the layout and the animation wouldn't properly start at the old position.
        mBackgroundBottom = value;
        mBackground.setBottom(value);
    }

    private float getBackgroundBottom() {
        if (mBackgroundBottom == -1) {
            return mBackground.getBottom();
        }
        return mBackgroundBottom;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    /**
     * If QS should have a solid or transparent background.
     */
    public void setBackgroundVisible(boolean visible) {
        mBackgroundVisible = visible;
        updateBackgroundVisibility();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanelContainer.getLayoutParams();

        // The footer is pinned to the bottom of QSPanel (same bottoms), therefore we don't need to
        // subtract its height. We do not care if the collapsed notifications fit in the screen.
        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();

        int padding = mPaddingLeft + mPaddingRight + layoutParams.leftMargin
                + layoutParams.rightMargin;
        final int qsPanelWidthSpec = getChildMeasureSpec(widthMeasureSpec, padding,
                layoutParams.width);
        mQSPanelContainer.measure(qsPanelWidthSpec,
                MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.AT_MOST));
        int width = mQSPanelContainer.getMeasuredWidth() + padding;
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanelContainer.getMeasuredHeight() + getPaddingBottom();
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }


    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanelContainer) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion(mAnimateBottomOnNextLayout /* animate */);
        mAnimateBottomOnNextLayout = false;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateBackgroundVisibility();
    }

    private void updateBackgroundVisibility() {
        mBackground.setVisibility(mQsDisabled || !mBackgroundVisible ? GONE : VISIBLE);
    }

    void updateResources(QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController) {
        LayoutParams layoutParams = (LayoutParams) mQSPanelContainer.getLayoutParams();
        layoutParams.topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mQSPanelContainer.setLayoutParams(layoutParams);

        mSideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        int padding = getResources().getDimensionPixelSize(
                R.dimen.notification_shade_content_margin_horizontal);
        boolean marginsChanged = padding != mContentPadding;
        mContentPadding = padding;
        if (marginsChanged) {
            updatePaddingsAndMargins(qsPanelController, quickStatusBarHeaderController);
        }
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        updateExpansion(false /* animate */);
    }

    public void updateExpansion(boolean animate) {
        int height = calculateContainerHeight();
        int scrollBottom = calculateContainerBottom();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + scrollBottom);
        mBackground.setTop(mQSPanelContainer.getTop());
        updateBackgroundBottom(scrollBottom, animate);
    }

    private void updateBackgroundBottom(int height, boolean animated) {
        PhysicsAnimator<QSContainerImpl> physicsAnimator = PhysicsAnimator.getInstance(this);
        if (physicsAnimator.isPropertyAnimating(BACKGROUND_BOTTOM) || animated) {
            // An animation is running or we want to animate
            // Let's make sure to set the currentValue again, since the call below might only
            // start in the next frame and otherwise we'd flicker
            BACKGROUND_BOTTOM.setValue(this, BACKGROUND_BOTTOM.getValue(this));
            physicsAnimator.spring(BACKGROUND_BOTTOM, height, BACKGROUND_SPRING).start();
        } else {
            BACKGROUND_BOTTOM.setValue(this, height);
        }

    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    int calculateContainerBottom() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion
                        * (heightOverride + mQSPanelContainer.getScrollRange()
                                - mQSPanelContainer.getScrollY() - mHeader.getHeight()))
                        + mHeader.getHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        updateExpansion();
    }

    private void updatePaddingsAndMargins(QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mQSCustomizer) {
                // Some views are always full width or have dependent padding
                continue;
            }
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.rightMargin = mSideMargins;
            lp.leftMargin = mSideMargins;
            if (view == mQSPanelContainer) {
                // QS panel lays out some of its content full width
                qsPanelController.setContentMargins(mContentPadding, mContentPadding);
                Pair<Integer, Integer> margins = qsPanelController.getVisualSideMargins();
                // Apply paddings based on QSPanel
                mQSCustomizer.setContentPaddings(margins.first, margins.second);
            } else if (view == mHeader) {
                // The header contains the QQS panel which needs to have special padding, to
                // visually align them.
                quickStatusBarHeaderController.setContentMargins(mContentPadding, mContentPadding);
            } else {
                view.setPaddingRelative(
                        mContentPadding,
                        view.getPaddingTop(),
                        mContentPadding,
                        view.getPaddingBottom());
            }
        }
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }
}
