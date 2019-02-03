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
 * limitations under the License.
 */

package com.android.systemui.bubbles;

import android.annotation.Nullable;
import android.app.ActivityView;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * A floating object on the screen that can post message updates.
 */
public class BubbleView extends FrameLayout implements BubbleTouchHandler.FloatingView {
    private static final String TAG = "BubbleView";

    // Same value as Launcher3 badge code
    private static final float WHITE_SCRIM_ALPHA = 0.54f;
    private Context mContext;

    private BadgedImageView mBadgedImageView;
    private TextView mMessageView;
    private int mPadding;
    private int mIconInset;

    private NotificationEntry mEntry;
    private PendingIntent mAppOverlayIntent;
    private BubbleController mBubbleController;
    private ActivityView mActivityView;
    private boolean mActivityViewReady;
    private boolean mActivityViewStarted;

    public BubbleView(Context context) {
        this(context, null);
    }

    public BubbleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mContext = context;
        // XXX: can this padding just be on the view and we look it up?
        mPadding = getResources().getDimensionPixelSize(R.dimen.bubble_view_padding);
        mIconInset = getResources().getDimensionPixelSize(R.dimen.bubble_icon_inset);
        mBubbleController = Dependency.get(BubbleController.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBadgedImageView = (BadgedImageView) findViewById(R.id.bubble_image);
        mMessageView = (TextView) findViewById(R.id.message_view);
        mMessageView.setVisibility(GONE);
        mMessageView.setPivotX(0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateViews();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        measureChild(mBadgedImageView, widthSpec, heightSpec);
        measureChild(mMessageView, widthSpec, heightSpec);
        boolean messageGone = mMessageView.getVisibility() == GONE;
        int imageHeight = mBadgedImageView.getMeasuredHeight();
        int imageWidth = mBadgedImageView.getMeasuredWidth();
        int messageHeight = messageGone ? 0 : mMessageView.getMeasuredHeight();
        int messageWidth = messageGone ? 0 : mMessageView.getMeasuredWidth();
        setMeasuredDimension(
                getPaddingStart() + imageWidth + mPadding + messageWidth + getPaddingEnd(),
                getPaddingTop() + Math.max(imageHeight, messageHeight) + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        left = getPaddingStart();
        top = getPaddingTop();
        int imageWidth = mBadgedImageView.getMeasuredWidth();
        int imageHeight = mBadgedImageView.getMeasuredHeight();
        int messageWidth = mMessageView.getMeasuredWidth();
        int messageHeight = mMessageView.getMeasuredHeight();
        mBadgedImageView.layout(left, top, left + imageWidth, top + imageHeight);
        mMessageView.layout(left + imageWidth + mPadding, top,
                left + imageWidth + mPadding + messageWidth, top + messageHeight);
    }

    /**
     * Populates this view with a notification.
     * <p>
     * This should only be called when a new notification is being set on the view, updates to the
     * current notification should use {@link #update(NotificationEntry)}.
     *
     * @param entry the notification to display as a bubble.
     */
    public void setNotif(NotificationEntry entry) {
        mEntry = entry;
        updateViews();
    }

    /**
     * The {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public NotificationEntry getEntry() {
        return mEntry;
    }

    /**
     * The key for the {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public String getKey() {
        return (mEntry != null) ? mEntry.key : null;
    }

    /**
     * Updates the UI based on the entry, updates badge and animates messages as needed.
     */
    public void update(NotificationEntry entry) {
        mEntry = entry;
        updateViews();
    }


    /**
     * @return the {@link ExpandableNotificationRow} view to display notification content when the
     * bubble is expanded.
     */
    @Nullable
    public ExpandableNotificationRow getRowView() {
        return (mEntry != null) ? mEntry.getRow() : null;
    }

    /**
     * Marks this bubble as "read", i.e. no badge should show.
     */
    public void updateDotVisibility() {
        boolean showDot = getEntry().showInShadeWhenBubble();
        animateDot(showDot);
    }

    /**
     * Animates the badge to show or hide.
     */
    private void animateDot(boolean showDot) {
        if (mBadgedImageView.isShowingDot() != showDot) {
            mBadgedImageView.setShowDot(showDot);
            mBadgedImageView.clearAnimation();
            mBadgedImageView.animate().setDuration(200)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setUpdateListener((valueAnimator) -> {
                        float fraction = valueAnimator.getAnimatedFraction();
                        fraction = showDot ? fraction : 1 - fraction;
                        mBadgedImageView.setDotScale(fraction);
                    }).withEndAction(() -> {
                if (!showDot) {
                    mBadgedImageView.setShowDot(false);
                }
            }).start();
        }
    }

    private void updateViews() {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        boolean isLarge = n.getLargeIcon() != null;
        Icon ic = isLarge ? n.getLargeIcon() : n.getSmallIcon();
        Drawable iconDrawable = ic.loadDrawable(mContext);
        if (!isLarge) {
            // Center icon on coloured background
            iconDrawable.setTint(Color.WHITE); // TODO: dark mode
            Drawable bg = new ColorDrawable(n.color);
            InsetDrawable d = new InsetDrawable(iconDrawable, mIconInset);
            Drawable[] layers = {bg, d};
            mBadgedImageView.setImageDrawable(new LayerDrawable(layers));
        } else {
            mBadgedImageView.setImageDrawable(iconDrawable);
        }
        int badgeColor = determineDominateColor(iconDrawable, n.color);
        mBadgedImageView.setDotColor(badgeColor);
        animateDot(mEntry.showInShadeWhenBubble() /* showDot */);
    }

    private int determineDominateColor(Drawable d, int defaultTint) {
        // XXX: should we pull from the drawable, app icon, notif tint?
        return ColorUtils.blendARGB(defaultTint, Color.WHITE, WHITE_SCRIM_ALPHA);
    }

    /**
     * @return a view used to display app overlay content when expanded.
     */
    public ActivityView getActivityView() {
        if (mActivityView == null) {
            mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                    true /* singleTaskInstance */);
            Log.d(TAG, "[getActivityView] created: " + mActivityView);
            mActivityView.setCallback(new ActivityView.StateCallback() {
                @Override
                public void onActivityViewReady(ActivityView view) {
                    mActivityViewReady = true;
                    mActivityView.startActivity(mAppOverlayIntent);
                }

                @Override
                public void onActivityViewDestroyed(ActivityView view) {
                    mActivityViewReady = false;
                }

                /**
                 * This is only called for tasks on this ActivityView, which is also set to
                 * single-task mode -- meaning never more than one task on this display. If a task
                 * is being removed, it's the top Activity finishing and this bubble should
                 * be removed or collapsed.
                 */
                @Override
                public void onTaskRemovalStarted(int taskId) {
                    if (mEntry != null) {
                        // Must post because this is called from a binder thread.
                        post(() -> mBubbleController.removeBubble(mEntry.key));
                    }
                }
            });
        }
        return mActivityView;
    }

    /**
     * Removes and releases an ActivityView if one was previously created for this bubble.
     */
    public void destroyActivityView(ViewGroup tmpParent) {
        if (mActivityView == null) {
            return;
        }
        if (!mActivityViewReady) {
            // release not needed, never initialized?
            mActivityView = null;
            return;
        }
        // HACK: release() will crash if the view is not attached.
        if (!mActivityView.isAttachedToWindow()) {
            mActivityView.setVisibility(View.GONE);
            tmpParent.addView(mActivityView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        mActivityView.release();

        ((ViewGroup) mActivityView.getParent()).removeView(mActivityView);
        mActivityView = null;
    }

    @Override
    public void setPosition(float x, float y) {
        setPositionX(x);
        setPositionY(y);
    }

    @Override
    public void setPositionX(float x) {
        setTranslationX(x);
    }

    @Override
    public void setPositionY(float y) {
        setTranslationY(y);
    }

    @Override
    public PointF getPosition() {
        return new PointF(getTranslationX(), getTranslationY());
    }

    /**
     * @return whether an ActivityView should be used to display the content of this Bubble
     */
    public boolean hasAppOverlayIntent() {
        return mAppOverlayIntent != null;
    }

    public PendingIntent getAppOverlayIntent() {
        return mAppOverlayIntent;

    }

    public void setBubbleIntent(PendingIntent intent) {
        mAppOverlayIntent = intent;
    }
}
