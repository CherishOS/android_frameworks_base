/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout implements BubbleTouchHandler.FloatingView {

    private Point mDisplaySize;

    private FrameLayout mBubbleContainer;
    private BubbleExpandedViewContainer mExpandedViewContainer;

    private int mBubbleSize;
    private int mBubblePadding;

    private boolean mIsExpanded;
    private BubbleView mExpandedBubble;
    private Point mCollapsedPosition;
    private BubbleTouchHandler mTouchHandler;
    private BubbleController.BubbleExpandListener mExpandListener;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsAnimating = false;

    // Used for determining view / touch intersection
    int[] mTempLoc = new int[2];
    RectF mTempRect = new RectF();

    private ViewTreeObserver.OnPreDrawListener mViewUpdater =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
                    applyCurrentState();
                    mViewUpdatedRequested = false;
                    return true;
                }
            };

    private ViewClippingUtil.ClippingParameters mClippingParameters =
            new ViewClippingUtil.ClippingParameters() {

        @Override
        public boolean shouldFinish(View view) {
            return false;
        }

        @Override
        public boolean isClippingEnablingAllowed(View view) {
            return !mIsExpanded;
        }
    };

    public BubbleStackView(Context context) {
        super(context);

        mTouchHandler = new BubbleTouchHandler(context);
        setOnTouchListener(mTouchHandler);

        Resources res = getResources();
        mBubbleSize = res.getDimensionPixelSize(R.dimen.bubble_size);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);

        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getSize(mDisplaySize);

        int padding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);
        mExpandedViewContainer = (BubbleExpandedViewContainer)
                LayoutInflater.from(context).inflate(R.layout.bubble_expanded_view,
                        this /* parent */, false /* attachToRoot */);
        mExpandedViewContainer.setElevation(elevation);
        mExpandedViewContainer.setPadding(padding, padding, padding, padding);
        mExpandedViewContainer.setClipChildren(false);
        addView(mExpandedViewContainer);

        mBubbleContainer = new FrameLayout(context);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setPadding(padding, 0, padding, 0);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer);

        setClipChildren(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
    }

    @Override
    public void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);

        int bubbleHeightSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightSpec),
                MeasureSpec.UNSPECIFIED);
        if (mIsExpanded) {
            ViewGroup parent = (ViewGroup) getParent();
            int parentWidth = MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(parent.getWidth()), MeasureSpec.EXACTLY);
            int parentHeight = MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(parent.getHeight()), MeasureSpec.EXACTLY);
            measureChild(mBubbleContainer, parentWidth, bubbleHeightSpec);

            int expandedViewHeight = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightSpec),
                    MeasureSpec.UNSPECIFIED);
            measureChild(mExpandedViewContainer, parentWidth, expandedViewHeight);
            setMeasuredDimension(widthSpec, parentHeight);
        } else {
            // Not expanded
            measureChild(mExpandedViewContainer, 0, 0);

            // Bubbles are translated a little to stack on top of each other
            widthSpec = MeasureSpec.makeMeasureSpec(getStackWidth(), MeasureSpec.EXACTLY);
            measureChild(mBubbleContainer, widthSpec, bubbleHeightSpec);

            heightSpec = MeasureSpec.makeMeasureSpec(mBubbleContainer.getMeasuredHeight(),
                    MeasureSpec.EXACTLY);
            setMeasuredDimension(widthSpec, heightSpec);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        float x = ev.getRawX();
        float y = ev.getRawY();
        // If we're expanded only intercept if the tap is outside of the widget container
        if (mIsExpanded && isIntersecting(mExpandedViewContainer, x, y)) {
            return false;
        } else {
            return isIntersecting(mBubbleContainer, x, y);
        }
    }

    /**
     * Sets the listener to notify when the bubble stack is expanded.
     */
    public void setExpandListener(BubbleController.BubbleExpandListener listener) {
        mExpandListener = listener;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * The {@link BubbleView} that is expanded, null if one does not exist.
     */
    public BubbleView getExpandedBubble() {
        return mExpandedBubble;
    }

    /**
     * Sets the bubble that should be expanded and expands if needed.
     */
    public void setExpandedBubble(BubbleView bubbleToExpand) {
        mExpandedBubble = bubbleToExpand;
        mIsExpanded = true;
        updateExpandedBubble();
        requestUpdate();
    }

    /**
     * Adds a bubble to the stack.
     */
    public void addBubble(BubbleView bubbleView) {
        mBubbleContainer.addView(bubbleView, 0,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        ViewClippingUtil.setClippingDeactivated(bubbleView, true, mClippingParameters);
        requestUpdate();
    }

    /**
     * Remove a bubble from the stack.
     */
    public void removeBubble(BubbleView bubbleView) {
        int removedIndex = mBubbleContainer.indexOfChild(bubbleView);
        mBubbleContainer.removeView(bubbleView);
        boolean wasExpanded = mIsExpanded;
        int bubbleCount = mBubbleContainer.getChildCount();
        if (bubbleView.equals(mExpandedBubble) && bubbleCount > 0) {
            // If we have other bubbles and are expanded go to the next one or previous
            // if the bubble removed was last
            int nextIndex = bubbleCount > removedIndex ? removedIndex : bubbleCount - 1;
            mExpandedBubble = (BubbleView) mBubbleContainer.getChildAt(nextIndex);
        }
        mIsExpanded = wasExpanded && mBubbleContainer.getChildCount() > 0;
        requestUpdate();
        if (wasExpanded && !mIsExpanded && mExpandListener != null) {
            mExpandListener.onBubbleExpandChanged(mIsExpanded, 1 /* amount */);
        }
    }

    /**
     * Updates a bubble in the stack.
     *
     * @param bubbleView the view to update in the stack.
     * @param entry the entry to update it with.
     */
    public void updateBubble(BubbleView bubbleView, NotificationData.Entry entry) {
        // TODO - move to top of bubble stack, make it show its update if it makes sense
        bubbleView.update(entry);
        if (bubbleView.equals(mExpandedBubble)) {
            requestUpdate();
        }
    }

    /**
     * @return the view the touch event is on
     */
    @Nullable
    public View getTargetView(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();
        if (mIsExpanded) {
            if (isIntersecting(mBubbleContainer, x, y)) {
                for (int i = 0; i < mBubbleContainer.getChildCount(); i++) {
                    BubbleView view = (BubbleView) mBubbleContainer.getChildAt(i);
                    if (isIntersecting(view, x, y)) {
                        return view;
                    }
                }
            } else if (isIntersecting(mExpandedViewContainer, x, y)) {
                return mExpandedViewContainer;
            }
            // Outside parts of view we care about.
            return null;
        }
        // If we're collapsed, the stack is always the target.
        return this;
    }

    /**
     * Tell the stack to animate to collapsed or expanded state.
     */
    public void animateExpansion(boolean shouldExpand) {
        if (mIsExpanded != shouldExpand) {
            mIsExpanded = shouldExpand;
            mExpandedBubble = shouldExpand ? getTopBubble() : null;
            updateExpandedBubble();

            if (mExpandListener != null) {
                mExpandListener.onBubbleExpandChanged(mIsExpanded, 1 /* amount */);
            }
            if (shouldExpand) {
                // Save current position so that we might return there
                savePosition();
            }

            // Determine the translation for the stack
            Point position = shouldExpand
                    ? BubbleController.getExpandPoint(this, mBubbleSize, mDisplaySize)
                    : mCollapsedPosition;
            int delay = shouldExpand ? 0 : 100;
            AnimatorSet translationAnim = BubbleMovementHelper.getTranslateAnim(this, position,
                    200, delay, null);
            if (!shouldExpand) {
                // First collapse the stack, then translate, maybe should expand at same time?
                animateStackExpansion(() -> translationAnim.start());
            } else {
                // First translate, then expand
                translationAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mIsAnimating = true;
                    }
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        animateStackExpansion(() -> mIsAnimating = false);
                    }
                });
                translationAnim.start();
            }
        }
    }

    /**
     * The width of the collapsed stack of bubbles.
     */
    public int getStackWidth() {
        return mBubblePadding * (mBubbleContainer.getChildCount() - 1)
                + mBubbleSize + mBubbleContainer.getPaddingEnd()
                + mBubbleContainer.getPaddingStart();
    }

    /**
     * Saves the current position of the stack, used to save user placement of the stack to
     * return to after an animation.
     */
    private void savePosition() {
        mCollapsedPosition = getPosition();
    }

    private BubbleView getTopBubble() {
        return getBubbleAt(0);
    }

    private BubbleView getBubbleAt(int i) {
        return mBubbleContainer.getChildCount() > i
                ? (BubbleView) mBubbleContainer.getChildAt(i)
                : null;
    }

    @Override
    public void setPosition(int x, int y) {
        setPositionX(x);
        setPositionY(y);
    }

    @Override
    public void setPositionX(int x) {
        setTranslationX(x);
    }

    @Override
    public void setPositionY(int y) {
        setTranslationY(y);
    }

    @Override
    public Point getPosition() {
        return new Point((int) getTranslationX(), (int) getTranslationY());
    }

    private boolean isIntersecting(View view, float x, float y) {
        mTempLoc = view.getLocationOnScreen();
        mTempRect.set(mTempLoc[0], mTempLoc[1], mTempLoc[0] + view.getWidth(),
                mTempLoc[1] + view.getHeight());
        return mTempRect.contains(x, y);
    }

    private void requestUpdate() {
        if (mViewUpdatedRequested || mIsAnimating) {
            return;
        }
        mViewUpdatedRequested = true;
        getViewTreeObserver().addOnPreDrawListener(mViewUpdater);
        invalidate();
    }

    private void updateExpandedBubble() {
        if (mExpandedBubble != null) {
            ExpandableNotificationRow row = mExpandedBubble.getRowView();
            if (!row.equals(mExpandedViewContainer.getChildAt(0))) {
                // Different expanded view than what we have
                mExpandedViewContainer.setExpandedView(null);
            }
            int pointerPosition = mExpandedBubble.getPosition().x
                    + (mExpandedBubble.getWidth() / 2);
            mExpandedViewContainer.setPointerPosition(pointerPosition);
            mExpandedViewContainer.setExpandedView(row);
        }
    }

    private void applyCurrentState() {
        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (!mIsExpanded) {
            mExpandedViewContainer.setExpandedView(null);
        } else {
            mExpandedViewContainer.setTranslationY(mBubbleContainer.getHeight());
            ExpandableNotificationRow row = mExpandedBubble.getRowView();
            applyRowState(row);
        }
        int bubbsCount = mBubbleContainer.getChildCount();
        for (int i = 0; i < bubbsCount; i++) {
            BubbleView bv = (BubbleView) mBubbleContainer.getChildAt(i);
            bv.setZ(bubbsCount - 1);

            int transX = mIsExpanded ? (bv.getWidth() + mBubblePadding) * i : mBubblePadding * i;
            ViewState viewState = new ViewState();
            viewState.initFrom(bv);
            viewState.xTranslation = transX;
            viewState.applyToView(bv);

            if (mIsExpanded) {
                // Save the position so we can magnet back, tag is retrieved in BubbleTouchHandler
                bv.setTag(new Point(transX, 0));
            }
        }
    }

    private void animateStackExpansion(Runnable endRunnable) {
        int childCount = mBubbleContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            BubbleView child = (BubbleView) mBubbleContainer.getChildAt(i);
            int transX = mIsExpanded ? (mBubbleSize + mBubblePadding) * i : mBubblePadding * i;
            int duration = childCount > 1 ? 200 : 0;
            if (mIsExpanded) {
                // Save the position so we can magnet back, tag is retrieved in BubbleTouchHandler
                child.setTag(new Point(transX, 0));
            }
            ViewPropertyAnimator anim = child
                    .animate()
                    .setStartDelay(15 * i)
                    .setDuration(duration)
                    .setInterpolator(mIsExpanded
                            ? new OvershootInterpolator()
                            : new AccelerateInterpolator())
                    .translationY(0)
                    .translationX(transX);
            final int fi = i;
            // Probably want this choreographed with translation somehow / make it snappier
            anim.withStartAction(() -> mIsAnimating = true);
            anim.withEndAction(() -> {
                if (endRunnable != null) {
                    endRunnable.run();
                }
                if (fi == mBubbleContainer.getChildCount() - 1) {
                    applyCurrentState();
                    mIsAnimating = false;
                    requestUpdate();
                }
            });
            anim.start();
        }
    }

    private void applyRowState(ExpandableNotificationRow view) {
        view.reset();
        view.setHeadsUp(false);
        view.setOnKeyguard(false);
        view.setOnAmbient(false);
        view.setClipBottomAmount(0);
        view.setClipTopAmount(0);
        view.setContentTransformationAmount(0, false);
        view.setIconsVisible(true);

        // TODO - Need to reset this (and others) when view goes back in shade, leave for now
        // view.setTopRoundness(1, false);
        // view.setBottomRoundness(1, false);

        ExpandableViewState viewState = view.getViewState();
        viewState = viewState == null ? new ExpandableViewState() : viewState;
        viewState.height = view.getIntrinsicHeight();
        viewState.gone = false;
        viewState.hidden = false;
        viewState.dimmed = false;
        viewState.dark = false;
        viewState.alpha = 1f;
        viewState.notGoneIndex = -1;
        viewState.xTranslation = 0;
        viewState.yTranslation = 0;
        viewState.zTranslation = 0;
        viewState.scaleX = 1;
        viewState.scaleY = 1;
        viewState.inShelf = true;
        viewState.headsUpIsVisible = false;
        viewState.applyToView(view);
    }
}
