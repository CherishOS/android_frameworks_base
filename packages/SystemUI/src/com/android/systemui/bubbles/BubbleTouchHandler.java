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

import static com.android.systemui.pip.phone.PipDismissViewController.SHOW_TARGET_DELAY;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.Dependency;
import com.android.systemui.pip.phone.PipDismissViewController;

/**
 * Handles interpreting touches on a {@link BubbleStackView}. This includes expanding, collapsing,
 * dismissing, and flings.
 */
class BubbleTouchHandler implements View.OnTouchListener {
    /** Velocity required to dismiss a bubble without dragging it into the dismiss target. */
    private static final float DISMISS_MIN_VELOCITY = 4000f;

    private static final String TAG = "BubbleTouchHandler";

    private final PointF mTouchDown = new PointF();
    private final PointF mViewPositionOnTouchDown = new PointF();
    private final BubbleStackView mStack;
    private final BubbleData mBubbleData;

    private BubbleController mController = Dependency.get(BubbleController.class);
    private PipDismissViewController mDismissViewController;

    private boolean mMovedEnough;
    private int mTouchSlopSquared;
    private VelocityTracker mVelocityTracker;

    private boolean mInDismissTarget;
    private Handler mHandler = new Handler();
    private Runnable mShowDismissAffordance = new Runnable() {
        @Override
        public void run() {
            mDismissViewController.showDismissTarget();
        }
    };

    /** View that was initially touched, when we received the first ACTION_DOWN event. */
    private View mTouchedView;

    BubbleTouchHandler(BubbleStackView stackView,
            BubbleData bubbleData, Context context) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquared = touchSlop * touchSlop;
        mDismissViewController = new PipDismissViewController(context);
        mBubbleData = bubbleData;
        mStack = stackView;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final int action = event.getActionMasked();

        // If we aren't currently in the process of touching a view, figure out what we're touching.
        // It'll be the stack, an individual bubble, or nothing.
        if (mTouchedView == null) {
            mTouchedView = mStack.getTargetView(event);
        }

        // If this is an ACTION_OUTSIDE event, or the stack reported that we aren't touching
        // anything, collapse the stack.
        if (action == MotionEvent.ACTION_OUTSIDE || mTouchedView == null) {
            mBubbleData.setExpanded(false);
            resetForNextGesture();
            return false;
        }

        final boolean isStack = mStack.equals(mTouchedView);
        final boolean isFlyout = mStack.getFlyoutView().equals(mTouchedView);
        final float rawX = event.getRawX();
        final float rawY = event.getRawY();

        // The coordinates of the touch event, in terms of the touched view's position.
        final float viewX = mViewPositionOnTouchDown.x + rawX - mTouchDown.x;
        final float viewY = mViewPositionOnTouchDown.y + rawY - mTouchDown.y;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackMovement(event);

                mTouchDown.set(rawX, rawY);

                if (!isFlyout) {
                    mDismissViewController.createDismissTarget();
                    mHandler.postDelayed(mShowDismissAffordance, SHOW_TARGET_DELAY);
                }

                if (isStack) {
                    mViewPositionOnTouchDown.set(mStack.getStackPosition());
                    mStack.onDragStart();
                } else if (isFlyout) {
                    // TODO(b/129768381): Make the flyout dismissable with a gesture.
                } else {
                    mViewPositionOnTouchDown.set(
                            mTouchedView.getTranslationX(), mTouchedView.getTranslationY());
                    mStack.onBubbleDragStart(mTouchedView);
                }

                break;
            case MotionEvent.ACTION_MOVE:
                trackMovement(event);
                final float deltaX = rawX - mTouchDown.x;
                final float deltaY = rawY - mTouchDown.y;

                if ((deltaX * deltaX) + (deltaY * deltaY) > mTouchSlopSquared && !mMovedEnough) {
                    mMovedEnough = true;
                }

                if (mMovedEnough) {
                    if (isStack) {
                        mStack.onDragged(viewX, viewY);
                    } else if (isFlyout) {
                        // TODO(b/129768381): Make the flyout dismissable with a gesture.
                    } else {
                        mStack.onBubbleDragged(mTouchedView, viewX, viewY);
                    }
                }

                // TODO - when we're in the target stick to it / animate in some way?
                mInDismissTarget = mDismissViewController.updateTarget(
                        isStack ? mStack.getBubbleAt(0) : mTouchedView);
                break;

            case MotionEvent.ACTION_CANCEL:
                resetForNextGesture();
                break;

            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mInDismissTarget && isStack) {
                    mController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
                    mStack.onDragFinishAsDismiss();
                } else if (isFlyout) {
                    // TODO(b/129768381): Expand if tapped, dismiss if swiped away.
                    if (!mBubbleData.isExpanded() && !mMovedEnough) {
                        mBubbleData.setExpanded(true);
                    }
                } else if (mMovedEnough) {
                    mVelocityTracker.computeCurrentVelocity(/* maxVelocity */ 1000);
                    final float velX = mVelocityTracker.getXVelocity();
                    final float velY = mVelocityTracker.getYVelocity();
                    if (isStack) {
                        mStack.onDragFinish(viewX, viewY, velX, velY);
                    } else {
                        final boolean dismissed = mInDismissTarget || velY > DISMISS_MIN_VELOCITY;
                        mStack.onBubbleDragFinish(
                                mTouchedView, viewX, viewY, velX, velY, /* dismissed */ dismissed);
                        if (dismissed) {
                            mController.removeBubble(((BubbleView) mTouchedView).getKey(),
                                    BubbleController.DISMISS_USER_GESTURE);
                        }
                    }
                } else if (mTouchedView == mStack.getExpandedBubbleView()) {
                    mBubbleData.setExpanded(false);
                } else if (isStack) {
                    // Toggle expansion
                    mBubbleData.setExpanded(!mBubbleData.isExpanded());
                } else {
                    final String key = ((BubbleView) mTouchedView).getKey();
                    mBubbleData.setSelectedBubble(mBubbleData.getBubbleWithKey(key));
                }

                resetForNextGesture();
                break;
        }

        return true;
    }

    /** Clears all touch-related state. */
    private void resetForNextGesture() {
        cleanUpDismissTarget();
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        mTouchedView = null;
        mMovedEnough = false;
        mInDismissTarget = false;
    }

    /**
     * Removes the dismiss target and cancels any pending callbacks to show it.
     */
    private void cleanUpDismissTarget() {
        mHandler.removeCallbacks(mShowDismissAffordance);
        mDismissViewController.destroyDismissTarget();
    }


    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
    }
}
