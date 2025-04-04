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
 * limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.NonNull;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.RemeasuringLinearLayout;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;

/** View that represents the quick settings tile panel (when expanded/pulled down). **/
public class QSPanel extends LinearLayout implements Tunable {

    public static final String QS_SHOW_AUTO_BRIGHTNESS =
            Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS;
    public static final String QS_SHOW_BRIGHTNESS_SLIDER =
            Settings.Secure.QS_SHOW_BRIGHTNESS_SLIDER;
    public static final String QS_BRIGHTNESS_SLIDER_POSITION =
            Settings.Secure.QS_BRIGHTNESS_SLIDER_POSITION;
    public static final String QS_TILE_ANIMATION_STYLE =
            "system:" + Settings.System.QS_TILE_ANIMATION_STYLE;
    public static final String QS_TILE_ANIMATION_DURATION =
            "system:" + Settings.System.QS_TILE_ANIMATION_DURATION;
    public static final String QS_TILE_ANIMATION_INTERPOLATOR =
            "system:" + Settings.System.QS_TILE_ANIMATION_INTERPOLATOR;
    public static final String QS_TILE_VERTICAL_LAYOUT =
            "system:" + Settings.System.QS_TILE_VERTICAL_LAYOUT;
    public static final String QS_TILE_LABEL_HIDE =
            "system:" + Settings.System.QS_TILE_LABEL_HIDE;
    public static final String QS_TILE_LABEL_SIZE =
            "system:" + Settings.System.QS_TILE_LABEL_SIZE;
    public static final String QS_LAYOUT_COLUMNS =
            "system:" + Settings.System.QS_LAYOUT_COLUMNS;
    public static final String QS_LAYOUT_COLUMNS_LANDSCAPE =
            "system:" + Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE;
    public static final String QQS_LAYOUT_ROWS =
            "system:" + Settings.System.QQS_LAYOUT_ROWS;
    public static final String QQS_LAYOUT_ROWS_LANDSCAPE =
            "system:" + Settings.System.QQS_LAYOUT_ROWS_LANDSCAPE;

    private static final String TAG = "QSPanel";

    protected final Context mContext;
    private final int mMediaTopMargin;
    private final int mMediaTotalBottomMargin;

    private Runnable mCollapseExpandAction;

    /**
     * The index where the content starts that needs to be moved between parents
     */
    private int mMovableContentStartIndex;

    @Nullable
    protected View mBrightnessView;
    protected View mAutoBrightnessView;

    @Nullable
    protected BrightnessSliderController mToggleSliderController;

    protected Runnable mBrightnessRunnable;

    protected boolean mTop;

    /** Whether or not the QS media player feature is enabled. */
    protected boolean mUsingMediaPlayer;

    protected boolean mExpanded;
    protected boolean mListening;
    protected boolean mIsAutomaticBrightnessAvailable = false;

    private final List<OnConfigurationChangedListener> mOnConfigurationChangedListeners =
            new ArrayList<>();

    @Nullable
    protected View mFooter;

    @Nullable
    private PageIndicator mFooterPageIndicator;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private boolean mUsingHorizontalLayout;

    @Nullable
    private LinearLayout mHorizontalLinearLayout;
    @Nullable
    protected LinearLayout mHorizontalContentContainer;

    @Nullable
    protected QSTileLayout mTileLayout;
    private float mSquishinessFraction = 1f;
    private final ArrayMap<View, Integer> mChildrenLayoutTop = new ArrayMap<>();
    private final Rect mClippingRect = new Rect();
    private ViewGroup mMediaHostView;
    private boolean mShouldMoveMediaOnExpansion = true;
    private QSLogger mQsLogger;
    /**
     * Specifies if we can collapse to QQS in current state. In split shade that should be always
     * false. It influences available accessibility actions.
     */
    private boolean mCanCollapse = true;

    private boolean mSceneContainerEnabled;

    protected int mAnimStyle;
    protected int mAnimDuration;
    protected int mInterpolatorType;

    public QSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUsingMediaPlayer = useQsMediaPlayer(context);
        mMediaTotalBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_bottom_margin_media);
        mMediaTopMargin = getResources().getDimensionPixelSize(
                R.dimen.qs_tile_margin_vertical);
        mContext = context;

        setOrientation(VERTICAL);

        mMovableContentStartIndex = getChildCount();

        mIsAutomaticBrightnessAvailable = getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);

        TunerService tunerService = Dependency.get(TunerService.class);
        mTop = tunerService.getValue(QS_BRIGHTNESS_SLIDER_POSITION, 0) == 0;
    }

    void initialize(QSLogger qsLogger) {
        mQsLogger = qsLogger;
        mTileLayout = getOrCreateTileLayout();

        if (mUsingMediaPlayer) {
            mHorizontalLinearLayout = new RemeasuringLinearLayout(mContext);
            mHorizontalLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mHorizontalLinearLayout.setVisibility(
                    mUsingHorizontalLayout ? View.VISIBLE : View.GONE);
            mHorizontalLinearLayout.setClipChildren(false);
            mHorizontalLinearLayout.setClipToPadding(false);

            mHorizontalContentContainer = new RemeasuringLinearLayout(mContext);
            mHorizontalContentContainer.setOrientation(LinearLayout.VERTICAL);
            setHorizontalContentContainerClipping();

            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            int marginSize = (int) mContext.getResources().getDimension(R.dimen.qs_media_padding);
            lp.setMarginStart(0);
            lp.setMarginEnd(marginSize);
            lp.gravity = Gravity.CENTER_VERTICAL;
            mHorizontalLinearLayout.addView(mHorizontalContentContainer, lp);

            lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
            addView(mHorizontalLinearLayout, lp);
        }
    }

    void setSceneContainerEnabled(boolean enabled) {
        mSceneContainerEnabled = enabled;
        if (mSceneContainerEnabled) {
            updatePadding();
        }
    }

    protected void setHorizontalContentContainerClipping() {
        mHorizontalContentContainer.setClipChildren(true);
        mHorizontalContentContainer.setClipToPadding(false);
        // Don't clip on the top, that way, secondary pages tiles can animate up
        // Clipping coordinates should be relative to this view, not absolute (parent coordinates)
        mHorizontalContentContainer.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((right - left) != (oldRight - oldLeft)
                            || ((bottom - top) != (oldBottom - oldTop))) {
                        mClippingRect.right = right - left;
                        mClippingRect.bottom = bottom - top;
                        mHorizontalContentContainer.setClipBounds(mClippingRect);
                    }
                });
        mClippingRect.left = 0;
        mClippingRect.top = -1000;
        mHorizontalContentContainer.setClipBounds(mClippingRect);
    }

    /**
     * Add brightness view above the tile layout.
     *
     * Used to add the brightness slider after construction.
     */
    public void setBrightnessView(@NonNull View view) {
        if (mBrightnessView != null) {
            removeView(mBrightnessView);
            mMovableContentStartIndex--;
        }
        mBrightnessView = view;
        mAutoBrightnessView = view.findViewById(R.id.brightness_icon);
        setBrightnessViewMargin(mTop);
        if (mBrightnessView != null) {
            addView(mBrightnessView);
            mMovableContentStartIndex++;
        }
    }

    protected void setBrightnessViewMargin(boolean top) {
        if (mBrightnessView != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mBrightnessView.getLayoutParams();
            if (top) {
                lp.topMargin = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.qs_top_brightness_margin_top);
                lp.bottomMargin = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.qs_top_brightness_margin_bottom);
            } else {
                lp.topMargin = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.qs_bottom_brightness_margin_top);
                lp.bottomMargin = 0;
            }
            mBrightnessView.setLayoutParams(lp);
        }
    }

    /** */
    public QSTileLayout getOrCreateTileLayout() {
        if (mTileLayout == null) {
            mTileLayout = (QSTileLayout) LayoutInflater.from(mContext)
                    .inflate(R.layout.qs_paged_tile_layout, this, false);
            mTileLayout.setLogger(mQsLogger);
            mTileLayout.setSquishinessFraction(mSquishinessFraction);
        }
        return mTileLayout;
    }

    public void setSquishinessFraction(float squishinessFraction) {
        if (Float.compare(squishinessFraction, mSquishinessFraction) == 0) {
            return;
        }
        mSquishinessFraction = squishinessFraction;
        if (mTileLayout == null) {
            return;
        }
        mTileLayout.setSquishinessFraction(squishinessFraction);
        if (getMeasuredWidth() == 0) {
            return;
        }
        updateViewPositions();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mTileLayout instanceof PagedTileLayout) {
            // Since PageIndicator gets measured before PagedTileLayout, we preemptively set the
            // # of pages before the measurement pass so PageIndicator is measured appropriately
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setNumPages(((PagedTileLayout) mTileLayout).getNumPages());
            }

            // In landscape, mTileLayout's parent is not the panel but a view that contains the
            // tile layout and the media controls.
            if (((View) mTileLayout).getParent() == this) {
                // Allow the UI to be as big as it want's to, we're in a scroll view
                int newHeight = 10000;
                int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
                int excessHeight = newHeight - availableHeight;
                // Measure with EXACTLY. That way, The content will only use excess height and will
                // be measured last, after other views and padding is accounted for. This only
                // works because our Layouts in here remeasure themselves with the exact content
                // height.
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY);
                ((PagedTileLayout) mTileLayout).setExcessHeight(excessHeight);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // We want all the logic of LinearLayout#onMeasure, and for it to assign the excess space
        // not used by the other children to PagedTileLayout. However, in this case, LinearLayout
        // assumes that PagedTileLayout would use all the excess space. This is not the case as
        // PagedTileLayout height is quantized (because it shows a certain number of rows).
        // Therefore, after everything is measured, we need to make sure that we add up the correct
        // total height
        int height = getPaddingBottom() + getPaddingTop();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                height += child.getMeasuredHeight();
                MarginLayoutParams layoutParams = (MarginLayoutParams) child.getLayoutParams();
                height += layoutParams.topMargin + layoutParams.bottomMargin;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            mChildrenLayoutTop.put(child, child.getTop());
        }
        updateViewPositions();
    }

    private void updateViewPositions() {
        if (mChildrenLayoutTop == null || mChildrenLayoutTop.isEmpty() || mTileLayout == null) {
            return;
        }
        // Adjust view positions based on tile squishing
        int tileHeightOffset = mTileLayout.getTilesHeight() - mTileLayout.getHeight();

        boolean move = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (move) {
                int topOffset;
                if (child == mMediaHostView && !mShouldMoveMediaOnExpansion) {
                    topOffset = 0;
                } else {
                    topOffset = tileHeightOffset;
                }
                // Animation can occur before the layout pass, meaning setSquishinessFraction() gets
                // called before onLayout(). So, a child view could be null because it has not
                // been added to mChildrenLayoutTop yet (which happens in onLayout()).
                // We use a continue statement here to catch this NPE because, on the layout pass,
                // this code will be called again from onLayout() with the populated children views.
                Integer childLayoutTop = mChildrenLayoutTop.get(child);
                if (childLayoutTop == null) {
                    continue;
                }
                int top = childLayoutTop;
                child.setLeftTopRightBottom(child.getLeft(), top + topOffset,
                        child.getRight(), top + topOffset + child.getHeight());
            }
            if (child == mTileLayout) {
                move = true;
            }
        }
    }

    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_SHOW_BRIGHTNESS_SLIDER:
                boolean value =
                       TunerService.parseInteger(newValue, 1) >= 1;
                if (mBrightnessView != null) {
                    mBrightnessView.setVisibility(value ? VISIBLE : GONE);
                }
                break;
            case QS_BRIGHTNESS_SLIDER_POSITION:
                mTop = TunerService.parseInteger(newValue, 0) == 0;
                updateBrightnessSliderPosition();
                break;
            case QS_SHOW_AUTO_BRIGHTNESS:
                if (mAutoBrightnessView != null) {
                    mAutoBrightnessView.setVisibility(mIsAutomaticBrightnessAvailable &&
                            TunerService.parseIntegerSwitch(newValue, true) ? View.VISIBLE : View.GONE);
                }
                break;
            case QS_TILE_ANIMATION_STYLE:
                mAnimStyle =
                       TunerService.parseInteger(newValue, 0);
                break;
            case QS_TILE_ANIMATION_DURATION:
                mAnimDuration =
                       TunerService.parseInteger(newValue, 1);
                break;
            case QS_TILE_ANIMATION_INTERPOLATOR:
                mInterpolatorType =
                       TunerService.parseInteger(newValue, 0);
                break;
            case QS_LAYOUT_COLUMNS:
            case QS_LAYOUT_COLUMNS_LANDSCAPE:
            case QQS_LAYOUT_ROWS:
            case QQS_LAYOUT_ROWS_LANDSCAPE:
                needsDynamicRowsAndColumns();
                break;
            default:
                break;
         }
    }

    public void setBrightnessRunnable(Runnable runnable) {
        mBrightnessRunnable = runnable;
    }


    @Nullable
    View getBrightnessView() {
        return mBrightnessView;
    }

    /**
     * Links the footer's page indicator, which is used in landscape orientation to save space.
     *
     * @param pageIndicator indicator to use for page scrolling
     */
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        if (mTileLayout instanceof PagedTileLayout) {
            mFooterPageIndicator = pageIndicator;
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        if (mTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setVisibility(View.GONE);

                ((PagedTileLayout) mTileLayout).setPageIndicator(mFooterPageIndicator);
            }
        }
    }

    public void updateResources() {
        updatePadding();

        updatePageIndicator();

        setBrightnessViewMargin(mTop);

        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    protected void updatePadding() {
        final Resources res = mContext.getResources();
        int paddingTop = res.getDimensionPixelSize(R.dimen.qs_panel_padding_top);
        int paddingBottom = res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom);
        setPaddingRelative(getPaddingStart(),
                mSceneContainerEnabled ? 0 : paddingTop,
                getPaddingEnd(),
                paddingBottom);
    }

    void addOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.add(listener);
    }

    void removeOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListeners.remove(listener);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mOnConfigurationChangedListeners.forEach(
                listener -> listener.onConfigurationChange(newConfig));
        needsDynamicRowsAndColumns();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFooter = findViewById(R.id.qs_footer);
    }

    private void updateHorizontalLinearLayoutMargins() {
        if (mHorizontalLinearLayout != null && !displayMediaMarginsOnMedia()) {
            LayoutParams lp = (LayoutParams) mHorizontalLinearLayout.getLayoutParams();
            lp.bottomMargin = Math.max(mMediaTotalBottomMargin - getPaddingBottom(), 0);
            mHorizontalLinearLayout.setLayoutParams(lp);
        }
    }

    /**
     * @return true if the margin bottom of the media view should be on the media host or false
     *         if they should be on the HorizontalLinearLayout. Returning {@code false} is useful
     *         to visually center the tiles in the Media view, which doesn't work when the
     *         expanded panel actually scrolls.
     */
    protected boolean displayMediaMarginsOnMedia() {
        return true;
    }

    /**
     * @return true if the media view needs margin on the top to separate it from the qs tiles
     */
    protected boolean mediaNeedsTopMargin() {
        return false;
    }

    public void needsDynamicRowsAndColumns() {
        if (mTileLayout != null) {
            mTileLayout.setMinRows(mTileLayout.getResourceRows());
            mTileLayout.setMaxColumns(mTileLayout.getResourceColumns());
        }
    }

    private void switchAllContentToParent(ViewGroup parent, QSTileLayout newLayout) {
        int index = parent == this ? mMovableContentStartIndex : 0;

        // Let's first move the tileLayout to the new parent, since that should come first.
        switchToParent((View) newLayout, parent, index);
        index++;

        if (mBrightnessView != null) {
            mTop = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.QS_BRIGHTNESS_SLIDER_POSITION,
                0, UserHandle.USER_CURRENT) == 0;
            if (!mUsingHorizontalLayout) {
                switchToParent(mBrightnessView, parent, mTop ? 0 : index);
                index++;
            } else {
                updateBrightnessSliderPosition();
            }
        }

        if (mFooter != null) {
            // Then the footer with the settings
            switchToParent(mFooter, parent, index);
            index++;
        }
    }

    private void switchToParent(View child, ViewGroup parent, int index) {
        switchToParent(child, parent, index, getDumpableTag());
    }

    /** Call when orientation has changed and MediaHost needs to be adjusted. */
    private void reAttachMediaHost(ViewGroup hostView, boolean horizontal) {
        if (!mUsingMediaPlayer) {
            return;
        }
        mMediaHostView = hostView;
        ViewGroup newParent = horizontal ? mHorizontalLinearLayout : this;
        ViewGroup currentParent = (ViewGroup) hostView.getParent();
        Log.d(getDumpableTag(), "Reattaching media host: " + horizontal
                + ", current " + currentParent + ", new " + newParent);
        if (currentParent != newParent) {
            if (currentParent != null) {
                currentParent.removeView(hostView);
            }
            newParent.addView(hostView);
            LinearLayout.LayoutParams layoutParams = (LayoutParams) hostView.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layoutParams.width = horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.weight = horizontal ? 1f : 0;
            // Add any bottom margin, such that the total spacing is correct. This is only
            // necessary if the view isn't horizontal, since otherwise the padding is
            // carried in the parent of this view (to ensure correct vertical alignment)
            layoutParams.bottomMargin = !horizontal || displayMediaMarginsOnMedia()
                    ? Math.max(mMediaTotalBottomMargin - getPaddingBottom(), 0) : 0;
            layoutParams.topMargin = mediaNeedsTopMargin() && !horizontal
                    ? mMediaTopMargin : 0;
            // Call setLayoutParams explicitly to ensure that requestLayout happens
            hostView.setLayoutParams(layoutParams);
        }
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        if (!mExpanded && mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setCurrentItem(0, false);
        }
    }

    public void setPageListener(final PagedTileLayout.PageListener pageListener) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageListener(pageListener);
        }
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    /** */
    public void setListening(boolean listening) {
        mListening = listening;
    }

    protected void drawTile(QSPanelControllerBase.TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
    }

    protected QSEvent openPanelEvent() {
        return QSEvent.QS_PANEL_EXPANDED;
    }

    protected QSEvent closePanelEvent() {
        return QSEvent.QS_PANEL_COLLAPSED;
    }

    protected QSEvent tileVisibleEvent() {
        return QSEvent.QS_TILE_VISIBLE;
    }

    protected boolean shouldShowDetail() {
        return mExpanded;
    }

    final void addTile(QSPanelControllerBase.TileRecord tileRecord) {
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                drawTile(tileRecord, state);
            }
        };

        tileRecord.tile.addCallback(callback);
        tileRecord.callback = callback;
        tileRecord.tileView.init(tileRecord.tile);
        tileRecord.tile.refreshState();

        if (mTileLayout != null) {
            mTileLayout.addTile(tileRecord);
            tileClickListener(tileRecord.tile, tileRecord.tileView);
        }
    }

    void removeTile(QSPanelControllerBase.TileRecord tileRecord) {
        mTileLayout.removeTile(tileRecord);
    }

    public int getGridHeight() {
        return getMeasuredHeight();
    }

    @Nullable
    QSTileLayout getTileLayout() {
        return mTileLayout;
    }

    /** */
    public void setContentMargins(int startMargin, int endMargin, ViewGroup mediaHostView) {
        // Only some views actually want this content padding, others want to go all the way
        // to the edge like the brightness slider
        mContentMarginStart = startMargin;
        mContentMarginEnd = endMargin;
        updateMediaHostContentMargins(mediaHostView);
    }

    /**
     * Update the margins of the media hosts
     */
    protected void updateMediaHostContentMargins(ViewGroup mediaHostView) {
        if (mUsingMediaPlayer) {
            int marginStart = 0;
            int marginEnd = 0;
            if (mUsingHorizontalLayout) {
                marginEnd = mContentMarginEnd;
            }
            updateMargins(mediaHostView, marginStart, marginEnd);
        }
    }

    /**
     * Update the margins of a view.
     *
     * @param view the view to adjust
     * @param start the start margin to set
     * @param end the end margin to set
     */
    protected void updateMargins(View view, int start, int end) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        if (lp != null) {
            lp.setMarginStart(start);
            lp.setMarginEnd(end);
            view.setLayoutParams(lp);
        }
    }

    public boolean isListening() {
        return mListening;
    }

    protected void setPageMargin(int pageMargin) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageMargin(pageMargin);
        }
    }

    void setUsingHorizontalLayout(boolean horizontal, ViewGroup mediaHostView, boolean force) {
        if (horizontal != mUsingHorizontalLayout || force) {
            Log.d(getDumpableTag(), "setUsingHorizontalLayout: " + horizontal + ", " + force);
            mUsingHorizontalLayout = horizontal;
            ViewGroup newParent = horizontal ? mHorizontalContentContainer : this;
            switchAllContentToParent(newParent, mTileLayout);
            reAttachMediaHost(mediaHostView, horizontal);
            needsDynamicRowsAndColumns();
            updateMargins(mediaHostView);
            if (mHorizontalLinearLayout == null) return;
            mHorizontalLinearLayout.setVisibility(horizontal ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMargins(ViewGroup mediaHostView) {
        updateMediaHostContentMargins(mediaHostView);
        updateHorizontalLinearLayoutMargins();
        updatePadding();
    }

    /**
     * Sets whether the media container should move during the expansion of the QS Panel.
     *
     * As the QS Panel expands and the QS unsquish, the views below the QS tiles move to adapt to
     * the new height of the QS tiles.
     *
     * In some cases this might not be wanted for media. One example is when there is a transition
     * animation of the media container happening on split shade lock screen.
     */
    public void setShouldMoveMediaOnExpansion(boolean shouldMoveMediaOnExpansion) {
        mShouldMoveMediaOnExpansion = shouldMoveMediaOnExpansion;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mCanCollapse) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND
                || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            if (mCollapseExpandAction != null) {
                mCollapseExpandAction.run();
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    public void setCollapseExpandAction(Runnable action) {
        mCollapseExpandAction = action;
    }

    /**
     * Specifies if these expanded QS can collapse to QQS.
     */
    public void setCanCollapse(boolean canCollapse) {
        mCanCollapse = canCollapse;
    }

    private void updateBrightnessSliderPosition() {
        if (mBrightnessView == null) return;
        removeView(mBrightnessView);
        addView(mBrightnessView, mTop ? 0 : 1);
        if (mBrightnessRunnable != null) {
            updateResources();
            mBrightnessRunnable.run();
        }
    }

    public interface QSTileLayout {
        /** */
        default void saveInstanceState(Bundle outState) {}

        /** */
        default void restoreInstanceState(Bundle savedInstanceState) {}

        /** */
        void addTile(QSPanelControllerBase.TileRecord tile);

        /** */
        void removeTile(QSPanelControllerBase.TileRecord tile);

        /** */
        int getOffsetTop(QSPanelControllerBase.TileRecord tile);

        /** */
        boolean updateResources();

        /** */
        void setListening(boolean listening, UiEventLogger uiEventLogger);

        /** */
        int getHeight();

        /** */
        int getTilesHeight();

        /**
         * Sets a size modifier for the tile. Where 0 means collapsed, and 1 expanded.
         */
        void setSquishinessFraction(float squishinessFraction);

        /**
         * Sets the minimum number of rows to show
         *
         * @param minRows the minimum.
         */
        default boolean setMinRows(int minRows) {
            return false;
        }

        /**
         * Sets the max number of columns to show
         *
         * @param maxColumns the maximum
         *
         * @return true if the number of visible columns has changed.
         */
        default boolean setMaxColumns(int maxColumns) {
            return false;
        }

        /**
         * Sets the expansion value and proposedTranslation to panel.
         */
        default void setExpansion(float expansion, float proposedTranslation) {}

        int getNumVisibleTiles();

        default void setLogger(QSLogger qsLogger) { }

        int getResourceColumns();

        int getResourceRows();

        void updateSettings();
    }

    interface OnConfigurationChangedListener {
        void onConfigurationChange(Configuration newConfig);
    }

    @VisibleForTesting
    static void switchToParent(View child, ViewGroup parent, int index, String tag) {
        if (parent == null) {
            Log.w(tag, "Trying to move view to null parent",
                    new IllegalStateException());
            return;
        }
        ViewGroup currentParent = (ViewGroup) child.getParent();
        if (currentParent != parent) {
            if (currentParent != null) {
                currentParent.removeView(child);
            }
            parent.addView(child, index);
            return;
        }
        // Same parent, we are just changing indices
        int currentIndex = parent.indexOfChild(child);
        if (currentIndex == index) {
            // We want to be in the same place. Nothing to do here
            return;
        }
        parent.removeView(child);
        parent.addView(child, index);
    }

    private void setAnimationTile(QSTileView v) {
        ObjectAnimator animTile = null;

        switch (mAnimStyle) {
            case 1:
                animTile = ObjectAnimator.ofFloat(v, "rotation", 0f, 360f);
                break;
            case 2:
                animTile = ObjectAnimator.ofFloat(v, "rotationX", 0f, 360f);
                break;
            case 3:
                animTile = ObjectAnimator.ofFloat(v, "rotationY", 0f, 360f);
                break;
            default:
                return;
        }

        switch (mInterpolatorType) {
            case 0:
                animTile.setInterpolator(new LinearInterpolator());
                break;
            case 1:
                animTile.setInterpolator(new AccelerateInterpolator());
                break;
            case 2:
                animTile.setInterpolator(new DecelerateInterpolator());
                break;
            case 3:
                animTile.setInterpolator(new AccelerateDecelerateInterpolator());
                break;
            case 4:
                animTile.setInterpolator(new BounceInterpolator());
                break;
            case 5:
                animTile.setInterpolator(new OvershootInterpolator());
                break;
            case 6:
                animTile.setInterpolator(new AnticipateInterpolator());
                break;
            case 7:
                animTile.setInterpolator(new AnticipateOvershootInterpolator());
                break;
            default:
                break;
        }
        animTile.setDuration(mAnimDuration * 1000);
        animTile.start();
    }

    private void tileClickListener(QSTile t, QSTileView v) {
        if (mTileLayout != null) {
            v.setOnClickListener(view -> {
                    t.click(view);
                    setAnimationTile(v);
            });
        }
    }
}
