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

package com.android.systemui.bubbles.animation;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * Animation controller for bubbles when they're in their expanded state, or animating to/from the
 * expanded state. This controls the expansion animation as well as bubbles 'dragging out' to be
 * dismissed.
 */
public class ExpandedAnimationController
        extends PhysicsAnimationLayout.PhysicsAnimationController {

    /**
     * How much to translate the bubbles when they're animating in/out. This value is multiplied by
     * the bubble size.
     */
    private static final int ANIMATE_TRANSLATION_FACTOR = 4;

    /** How much to scale down bubbles when they're animating in/out. */
    private static final float ANIMATE_SCALE_PERCENT = 0.5f;

    /** The stack position to collapse back to in {@link #collapseBackToStack}. */
    private PointF mCollapseToPoint;

    /** Horizontal offset between bubbles, which we need to know to re-stack them. */
    private float mStackOffsetPx;
    /** Space between status bar and bubbles in the expanded state. */
    private float mBubblePaddingTop;
    /** Size of each bubble. */
    private float mBubbleSizePx;
    /** Height of the status bar. */
    private float mStatusBarHeight;
    /** Size of display. */
    private Point mDisplaySize;
    /** Max number of bubbles shown in row above expanded view.*/
    private int mBubblesMaxRendered;
    /** Width of current screen orientation. */
    private float mScreenWidth;

    /** Whether the dragged-out bubble is in the dismiss target. */
    private boolean mIndividualBubbleWithinDismissTarget = false;

    private boolean mAnimatingExpand = false;
    private boolean mAnimatingCollapse = false;
    private Runnable mAfterExpand;
    private Runnable mAfterCollapse;
    private PointF mCollapsePoint;

    /**
     * Whether the dragged out bubble is springing towards the touch point, rather than using the
     * default behavior of moving directly to the touch point.
     *
     * This happens when the user's finger exits the dismiss area while the bubble is magnetized to
     * the center. Since the touch point differs from the bubble location, we need to animate the
     * bubble back to the touch point to avoid a jarring instant location change from the center of
     * the target to the touch point just outside the target bounds.
     */
    private boolean mSpringingBubbleToTouch = false;

    private int mExpandedViewPadding;
    private float mLauncherGridDiff;

    public ExpandedAnimationController(Point displaySize, int expandedViewPadding,
            int orientation) {
        mDisplaySize = displaySize;
        updateOrientation(orientation);
        mExpandedViewPadding = expandedViewPadding;
        mLauncherGridDiff = 30f;
    }

    /**
     * Whether the individual bubble has been dragged out of the row of bubbles far enough to cause
     * the rest of the bubbles to animate to fill the gap.
     */
    private boolean mBubbleDraggedOutEnough = false;

    /** The bubble currently being dragged out of the row (to potentially be dismissed). */
    private View mBubbleDraggingOut;

    /**
     * Animates expanding the bubbles into a row along the top of the screen.
     */
    public void expandFromStack(Runnable after) {
        mAnimatingCollapse = false;
        mAnimatingExpand = true;
        mAfterExpand = after;

        startOrUpdateExpandAnimation();
    }

    /** Animate collapsing the bubbles back to their stacked position. */
    public void collapseBackToStack(PointF collapsePoint, Runnable after) {
        mAnimatingExpand = false;
        mAnimatingCollapse = true;
        mAfterCollapse = after;
        mCollapsePoint = collapsePoint;

        startOrUpdateCollapseAnimation();
    }

    /**
     * Update effective screen width based on current orientation.
     * @param orientation Landscape or portrait.
     */
    public void updateOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mScreenWidth = mDisplaySize.y;
            return;
        }
        mScreenWidth = mDisplaySize.x;
    }

    private void startOrUpdateExpandAnimation() {
        animationsForChildrenFromIndex(
                0, /* startIndex */
                (index, animation) -> animation.position(getBubbleLeft(index), getExpandedY()))
                .startAll(() -> {
                    mAnimatingExpand = false;

                    if (mAfterExpand != null) {
                        mAfterExpand.run();
                    }

                    mAfterExpand = null;
                });
    }

    private void startOrUpdateCollapseAnimation() {
        // Stack to the left if we're going to the left, or right if not.
        final float sideMultiplier = mLayout.isFirstChildXLeftOfCenter(mCollapsePoint.x) ? -1 : 1;
        animationsForChildrenFromIndex(
                0, /* startIndex */
                (index, animation) -> {
                    animation.position(
                            mCollapsePoint.x + (sideMultiplier * index * mStackOffsetPx),
                            mCollapsePoint.y);
                })
                .startAll(() -> {
                    mAnimatingCollapse = false;

                    if (mAfterCollapse != null) {
                        mAfterCollapse.run();
                    }

                    mAfterCollapse = null;
                });
    }

    /** Prepares the given bubble to be dragged out. */
    public void prepareForBubbleDrag(View bubble) {
        mLayout.cancelAnimationsOnView(bubble);

        mBubbleDraggingOut = bubble;
        mBubbleDraggingOut.setTranslationZ(Short.MAX_VALUE);
    }

    /**
     * Drags an individual bubble to the given coordinates. Bubbles to the right will animate to
     * take its place once it's dragged out of the row of bubbles, and animate out of the way if the
     * bubble is dragged back into the row.
     */
    public void dragBubbleOut(View bubbleView, float x, float y) {
        if (mSpringingBubbleToTouch) {
            if (mLayout.arePropertiesAnimatingOnView(
                    bubbleView, DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y)) {
                animationForChild(mBubbleDraggingOut)
                        .translationX(x)
                        .translationY(y)
                        .withStiffness(SpringForce.STIFFNESS_HIGH)
                        .start();
            } else {
                mSpringingBubbleToTouch = false;
            }
        }

        if (!mSpringingBubbleToTouch && !mIndividualBubbleWithinDismissTarget) {
            bubbleView.setTranslationX(x);
            bubbleView.setTranslationY(y);
        }

        final boolean draggedOutEnough =
                y > getExpandedY() + mBubbleSizePx || y < getExpandedY() - mBubbleSizePx;
        if (draggedOutEnough != mBubbleDraggedOutEnough) {
            updateBubblePositions();
            mBubbleDraggedOutEnough = draggedOutEnough;
        }
    }

    /** Plays a dismiss animation on the dragged out bubble. */
    public void dismissDraggedOutBubble(View bubble, Runnable after) {
        mIndividualBubbleWithinDismissTarget = false;

        animationForChild(bubble)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .scaleX(1.1f)
                .scaleY(1.1f)
                .alpha(0f, after)
                .start();

        updateBubblePositions();
    }

    @Nullable public View getDraggedOutBubble() {
        return mBubbleDraggingOut;
    }

    /** Magnets the given bubble to the dismiss target. */
    public void magnetBubbleToDismiss(
            View bubbleView, float velX, float velY, float destY, Runnable after) {
        mIndividualBubbleWithinDismissTarget = true;
        mSpringingBubbleToTouch = false;
        animationForChild(bubbleView)
                .withStiffness(SpringForce.STIFFNESS_MEDIUM)
                .withDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .withPositionStartVelocities(velX, velY)
                .translationX(mLayout.getWidth() / 2f - mBubbleSizePx / 2f)
                .translationY(destY, after)
                .start();
    }

    /**
     * Springs the dragged-out bubble towards the given coordinates and sets flags to have touch
     * events update the spring's final position until it's settled.
     */
    public void demagnetizeBubbleTo(float x, float y, float velX, float velY) {
        mIndividualBubbleWithinDismissTarget = false;
        mSpringingBubbleToTouch = true;

        animationForChild(mBubbleDraggingOut)
                .translationX(x)
                .translationY(y)
                .withPositionStartVelocities(velX, velY)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .start();
    }

    /**
     * Snaps a bubble back to its position within the bubble row, and animates the rest of the
     * bubbles to accommodate it if it was previously dragged out past the threshold.
     */
    public void snapBubbleBack(View bubbleView, float velX, float velY) {
        final int index = mLayout.indexOfChild(bubbleView);

        animationForChildAtIndex(index)
                .position(getBubbleLeft(index), getExpandedY())
                .withPositionStartVelocities(velX, velY)
                .start(() -> bubbleView.setTranslationZ(0f) /* after */);

        updateBubblePositions();
    }

    /** Resets bubble drag out gesture flags. */
    public void onGestureFinished() {
        mBubbleDraggedOutEnough = false;
        mBubbleDraggingOut = null;
        updateBubblePositions();
    }

    /**
     * Animates the bubbles to {@link #getExpandedY()} position. Used in response to IME showing.
     */
    public void updateYPosition(Runnable after) {
        if (mLayout == null) return;
        animationsForChildrenFromIndex(
                0, (i, anim) -> anim.translationY(getExpandedY())).startAll(after);
    }

    /** The Y value of the row of expanded bubbles. */
    public float getExpandedY() {
        if (mLayout == null || mLayout.getRootWindowInsets() == null) {
            return 0;
        }
        final WindowInsets insets = mLayout.getRootWindowInsets();
        return mBubblePaddingTop + Math.max(
            mStatusBarHeight,
            insets.getDisplayCutout() != null
                ? insets.getDisplayCutout().getSafeInsetTop()
                : 0);
    }

    @Override
    void onActiveControllerForLayout(PhysicsAnimationLayout layout) {
        final Resources res = layout.getResources();
        mStackOffsetPx = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleSizePx = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mBubblesMaxRendered = res.getInteger(R.integer.bubbles_max_rendered);

        // Ensure that all child views are at 1x scale, and visible, in case they were animating
        // in.
        mLayout.setVisibility(View.VISIBLE);
        animationsForChildrenFromIndex(0 /* startIndex */, (index, animation) ->
                animation.scaleX(1f).scaleY(1f).alpha(1f)).startAll();
    }

    @Override
    Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
        return Sets.newHashSet(
                DynamicAnimation.TRANSLATION_X,
                DynamicAnimation.TRANSLATION_Y,
                DynamicAnimation.SCALE_X,
                DynamicAnimation.SCALE_Y,
                DynamicAnimation.ALPHA);
    }

    @Override
    int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index) {
        return NONE;
    }

    @Override
    float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property) {
        return 0;
    }

    @Override
    SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
        return new SpringForce()
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW);
    }

    @Override
    void onChildAdded(View child, int index) {
        // If a bubble is added while the expand/collapse animations are playing, update the
        // animation to include the new bubble.
        if (mAnimatingExpand) {
            startOrUpdateExpandAnimation();
        } else if (mAnimatingCollapse) {
            startOrUpdateCollapseAnimation();
        } else {
            child.setTranslationX(getBubbleLeft(index));
            animationForChild(child)
                    .translationY(
                            getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR, /* from */
                            getExpandedY() /* to */)
                    .start();
            updateBubblePositions();
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        final PhysicsAnimationLayout.PhysicsPropertyAnimator animator = animationForChild(child);

        // If we're removing the dragged-out bubble, that means it got dismissed.
        if (child.equals(mBubbleDraggingOut)) {
            mBubbleDraggingOut = null;
            finishRemoval.run();
        } else {
            animator.alpha(0f, finishRemoval /* endAction */)
                    .withStiffness(SpringForce.STIFFNESS_HIGH)
                    .withDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .start();
        }

        // Animate all the other bubbles to their new positions sans this bubble.
        updateBubblePositions();
    }

    @Override
    void onChildReordered(View child, int oldIndex, int newIndex) {
        updateBubblePositions();
    }

    private void updateBubblePositions() {
        if (mAnimatingExpand || mAnimatingCollapse) {
            return;
        }

        for (int i = 0; i < mLayout.getChildCount(); i++) {
            final View bubble = mLayout.getChildAt(i);

            // Don't animate the dragging out bubble, or it'll jump around while being dragged. It
            // will be snapped to the correct X value after the drag (if it's not dismissed).
            if (bubble.equals(mBubbleDraggingOut)) {
                return;
            }

            animationForChild(bubble)
                    .translationX(getBubbleLeft(i))
                    .start();
        }
    }

    /**
     * @param index Bubble index in row.
     * @return Bubble left x from left edge of screen.
     */
    public float getBubbleLeft(int index) {
        final float bubbleFromRowLeft = index * (mBubbleSizePx + getSpaceBetweenBubbles());
        return getRowLeft() + bubbleFromRowLeft;
    }

    private float getRowLeft() {
        if (mLayout == null) {
            return 0;
        }

        int bubbleCount = mLayout.getChildCount();

        final float totalBubbleWidth = bubbleCount * mBubbleSizePx;
        final float totalGapWidth = (bubbleCount - 1) * getSpaceBetweenBubbles();
        final float rowWidth = totalGapWidth + totalBubbleWidth;

        final float centerScreen = mScreenWidth / 2f;
        final float halfRow = rowWidth / 2f;
        final float rowLeft = centerScreen - halfRow;

        return rowLeft;
    }

    /**
     * @return Space between bubbles in row above expanded view.
     */
    private float getSpaceBetweenBubbles() {
        /**
         * Ordered left to right:
         *  Screen edge
         *      [mExpandedViewPadding]
         *  Expanded view edge
         *      [launcherGridDiff] --- arbitrary value until launcher exports widths
         *  Launcher's app icon grid edge that we must match
         */
        final float rowMargins = (mExpandedViewPadding + mLauncherGridDiff) * 2;
        final float maxRowWidth = mScreenWidth - rowMargins;

        final float totalBubbleWidth = mBubblesMaxRendered * mBubbleSizePx;
        final float totalGapWidth = maxRowWidth - totalBubbleWidth;

        final int gapCount = mBubblesMaxRendered - 1;
        final float gapWidth = totalGapWidth / gapCount;
        return gapWidth;
    }
}
