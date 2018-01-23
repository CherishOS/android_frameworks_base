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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.support.annotation.DimenRes;
import com.android.systemui.Dependency;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.utilities.Utilities;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static com.android.systemui.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.OverviewProxyService.TAG_OPS;

/**
 * Class to detect gestures on the navigation bar and implement quick scrub and switch.
 */
public class QuickScrubController extends GestureDetector.SimpleOnGestureListener implements
        GestureHelper {

    private static final String TAG = "QuickScrubController";
    private static final int QUICK_SWITCH_FLING_VELOCITY = 0;
    private static final int ANIM_DURATION_MS = 200;
    private static final long LONG_PRESS_DELAY_MS = 150;

    /**
     * For quick step, set a damping value to allow the button to stick closer its origin position
     * when dragging before quick scrub is active.
     */
    private static final int SWITCH_STICKINESS = 4;

    private NavigationBarView mNavigationBarView;
    private GestureDetector mGestureDetector;

    private boolean mDraggingActive;
    private boolean mQuickScrubActive;
    private float mDownOffset;
    private float mTranslation;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDragPositive;
    private boolean mIsVertical;
    private boolean mIsRTL;
    private float mMaxTrackPaintAlpha;

    private final Handler mHandler = new Handler();
    private final Interpolator mQuickScrubEndInterpolator = new DecelerateInterpolator();
    private final Rect mTrackRect = new Rect();
    private final Rect mHomeButtonRect = new Rect();
    private final Paint mTrackPaint = new Paint();
    private final int mScrollTouchSlop;
    private final OverviewProxyService mOverviewEventSender;
    private final Display mDisplay;
    private final int mTrackThickness;
    private final int mTrackPadding;
    private final ValueAnimator mTrackAnimator;
    private final ValueAnimator mButtonAnimator;
    private final AnimatorSet mQuickScrubEndAnimator;
    private final Context mContext;

    private final AnimatorUpdateListener mTrackAnimatorListener = valueAnimator -> {
        mTrackPaint.setAlpha(Math.round((float) valueAnimator.getAnimatedValue() * 255));
        mNavigationBarView.invalidate();
    };

    private final AnimatorUpdateListener mButtonTranslationListener = animator -> {
        int pos = (int) animator.getAnimatedValue();
        if (!mQuickScrubActive) {
            pos = mDragPositive ? Math.min((int) mTranslation, pos) : Math.max((int) mTranslation, pos);
        }
        final View homeView = mNavigationBarView.getHomeButton().getCurrentView();
        if (mIsVertical) {
            homeView.setTranslationY(pos);
        } else {
            homeView.setTranslationX(pos);
        }
    };

    private AnimatorListenerAdapter mQuickScrubEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mNavigationBarView.getHomeButton().setClickable(true);
            mQuickScrubActive = false;
            mTranslation = 0;
        }
    };

    private Runnable mLongPressRunnable = this::startQuickScrub;

    private final GestureDetector.SimpleOnGestureListener mGestureListener =
        new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velX, float velY) {
                if (!isQuickScrubEnabled() || mQuickScrubActive) {
                    return false;
                }
                float velocityX = mIsRTL ? -velX : velX;
                float absVelY = Math.abs(velY);
                final boolean isValidFling = velocityX > QUICK_SWITCH_FLING_VELOCITY &&
                        mIsVertical ? (absVelY > velocityX) : (velocityX > absVelY);
                if (isValidFling) {
                    mDraggingActive = false;
                    mButtonAnimator.setIntValues((int) mTranslation, 0);
                    mButtonAnimator.start();
                    mHandler.removeCallbacks(mLongPressRunnable);
                    try {
                        final IOverviewProxy overviewProxy = mOverviewEventSender.getProxy();
                        overviewProxy.onQuickSwitch();
                        if (DEBUG_OVERVIEW_PROXY) {
                            Log.d(TAG_OPS, "Quick Switch");
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to send start of quick switch.", e);
                    }
                }
                return true;
            }
        };

    public QuickScrubController(Context context) {
        mContext = context;
        mScrollTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mDisplay = ((WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mOverviewEventSender = Dependency.get(OverviewProxyService.class);
        mGestureDetector = new GestureDetector(mContext, mGestureListener);
        mTrackThickness = getDimensionPixelSize(mContext, R.dimen.nav_quick_scrub_track_thickness);
        mTrackPadding = getDimensionPixelSize(mContext, R.dimen.nav_quick_scrub_track_edge_padding);

        mTrackAnimator = ObjectAnimator.ofFloat();
        mTrackAnimator.addUpdateListener(mTrackAnimatorListener);
        mButtonAnimator = ObjectAnimator.ofInt();
        mButtonAnimator.addUpdateListener(mButtonTranslationListener);
        mQuickScrubEndAnimator = new AnimatorSet();
        mQuickScrubEndAnimator.playTogether(mTrackAnimator, mButtonAnimator);
        mQuickScrubEndAnimator.setDuration(ANIM_DURATION_MS);
        mQuickScrubEndAnimator.addListener(mQuickScrubEndListener);
        mQuickScrubEndAnimator.setInterpolator(mQuickScrubEndInterpolator);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        final IOverviewProxy overviewProxy = mOverviewEventSender.getProxy();
        final ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        if (overviewProxy == null) {
            homeButton.setDelayTouchFeedback(false);
            return false;
        }
        mGestureDetector.onTouchEvent(event);
        int action = event.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                if (isQuickScrubEnabled() && mHomeButtonRect.contains(x, y)) {
                    mTouchDownX = x;
                    mTouchDownY = y;
                    homeButton.setDelayTouchFeedback(true);
                    mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DELAY_MS);
                } else {
                    homeButton.setDelayTouchFeedback(false);
                    mTouchDownX = mTouchDownY = -1;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mTouchDownX != -1) {
                    int x = (int) event.getX();
                    int y = (int) event.getY();
                    int xDiff = Math.abs(x - mTouchDownX);
                    int yDiff = Math.abs(y - mTouchDownY);
                    boolean exceededTouchSlopX = xDiff > mScrollTouchSlop && xDiff > yDiff;
                    boolean exceededTouchSlopY = yDiff > mScrollTouchSlop && yDiff > xDiff;
                    boolean exceededTouchSlop, exceededPerpendicularTouchSlop;
                    int pos, touchDown, offset, trackSize;

                    if (mIsVertical) {
                        exceededTouchSlop = exceededTouchSlopY;
                        exceededPerpendicularTouchSlop = exceededTouchSlopX;
                        pos = y;
                        touchDown = mTouchDownY;
                        offset = pos - mTrackRect.top;
                        trackSize = mTrackRect.height();
                    } else {
                        exceededTouchSlop = exceededTouchSlopX;
                        exceededPerpendicularTouchSlop = exceededTouchSlopY;
                        pos = x;
                        touchDown = mTouchDownX;
                        offset = pos - mTrackRect.left;
                        trackSize = mTrackRect.width();
                    }
                    // Do not start scrubbing when dragging in the perpendicular direction
                    if (!mDraggingActive && exceededPerpendicularTouchSlop) {
                        mHandler.removeCallbacksAndMessages(null);
                        return false;
                    }
                    if (!mDragPositive) {
                        offset -= mIsVertical ? mTrackRect.height() : mTrackRect.width();
                    }

                    // Control the button movement
                    if (!mDraggingActive && exceededTouchSlop) {
                        boolean allowDrag = !mDragPositive
                                ? offset < 0 && pos < touchDown : offset >= 0 && pos > touchDown;
                        if (allowDrag) {
                            mDownOffset = offset;
                            homeButton.setClickable(false);
                            mDraggingActive = true;
                        }
                    }
                    if (mDraggingActive && (mDragPositive && offset >= 0
                            || !mDragPositive && offset <= 0)) {
                        float scrubFraction =
                                Utilities.clamp(Math.abs(offset) * 1f / trackSize, 0, 1);
                        mTranslation = !mDragPositive
                            ? Utilities.clamp(offset - mDownOffset, -trackSize, 0)
                            : Utilities.clamp(offset - mDownOffset, 0, trackSize);
                        if (mQuickScrubActive) {
                            try {
                                overviewProxy.onQuickScrubProgress(scrubFraction);
                                if (DEBUG_OVERVIEW_PROXY) {
                                    Log.d(TAG_OPS, "Quick Scrub Progress:" + scrubFraction);
                                }
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to send progress of quick scrub.", e);
                            }
                        } else {
                            mTranslation /= SWITCH_STICKINESS;
                        }
                        if (mIsVertical) {
                            homeButton.getCurrentView().setTranslationY(mTranslation);
                        } else {
                            homeButton.getCurrentView().setTranslationX(mTranslation);
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                endQuickScrub();
                break;
        }
        return mDraggingActive || mQuickScrubActive;
    }

    @Override
    public void onDraw(Canvas canvas) {
        canvas.drawRect(mTrackRect, mTrackPaint);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width = right - left;
        final int height = bottom - top;
        final int x1, x2, y1, y2;
        if (mIsVertical) {
            x1 = (width - mTrackThickness) / 2;
            x2 = x1 + mTrackThickness;
            y1 = mDragPositive ? height / 2 : mTrackPadding;
            y2 = y1 + height / 2 - mTrackPadding;
        } else {
            y1 = (height - mTrackThickness) / 2;
            y2 = y1 + mTrackThickness;
            x1 = mDragPositive ? width / 2 : mTrackPadding;
            x2 = x1 + width / 2 - mTrackPadding;
        }
        mTrackRect.set(x1, y1, x2, y2);

        // Get the touch rect of the home button location
        View homeView = mNavigationBarView.getHomeButton().getCurrentView();
        if (homeView != null) {
            int[] globalHomePos = homeView.getLocationOnScreen();
            int[] globalNavBarPos = mNavigationBarView.getLocationOnScreen();
            int homeX = globalHomePos[0] - globalNavBarPos[0];
            int homeY = globalHomePos[1] - globalNavBarPos[1];
            mHomeButtonRect.set(homeX, homeY, homeX + homeView.getMeasuredWidth(),
                    homeY + homeView.getMeasuredHeight());
        }
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        if (intensity == 0) {
            mTrackPaint.setColor(mContext.getColor(R.color.quick_step_track_background_light));
        } else if (intensity == 1) {
            mTrackPaint.setColor(mContext.getColor(R.color.quick_step_track_background_dark));
        }
        mMaxTrackPaintAlpha = mTrackPaint.getAlpha() * 1f / 255;
        mTrackPaint.setAlpha(0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            endQuickScrub();
        }
        return false;
    }

    @Override
    public void setBarState(boolean isVertical, boolean isRTL) {
        mIsVertical = isVertical;
        mIsRTL = isRTL;
        try {
            int navbarPos = WindowManagerGlobal.getWindowManagerService().getNavBarPosition();
            mDragPositive = navbarPos == NAV_BAR_LEFT || navbarPos == NAV_BAR_BOTTOM;
            if (isRTL) {
                mDragPositive = !mDragPositive;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get nav bar position.", e);
        }
    }

    boolean isQuickScrubEnabled() {
        return SystemProperties.getBoolean("persist.quickstep.scrub.enabled", false);
    }

    private void startQuickScrub() {
        if (!mQuickScrubActive) {
            mQuickScrubActive = true;
            mTrackAnimator.setFloatValues(0, mMaxTrackPaintAlpha);
            mTrackAnimator.start();
            try {
                mOverviewEventSender.getProxy().onQuickScrubStart();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub Start");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send start of quick scrub.", e);
            }
        }
    }

    private void endQuickScrub() {
        mHandler.removeCallbacks(mLongPressRunnable);
        if (mDraggingActive || mQuickScrubActive) {
            mButtonAnimator.setIntValues((int) mTranslation, 0);
            mTrackAnimator.setFloatValues(mTrackPaint.getAlpha() * 1f / 255, 0);
            mQuickScrubEndAnimator.start();
            try {
                mOverviewEventSender.getProxy().onQuickScrubEnd();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub End");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send end of quick scrub.", e);
            }
        }
        mDraggingActive = false;
    }

    private int getDimensionPixelSize(Context context, @DimenRes int resId) {
        return context.getResources().getDimensionPixelSize(resId);
    }
}
