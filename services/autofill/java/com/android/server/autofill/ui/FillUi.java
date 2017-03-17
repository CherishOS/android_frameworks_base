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
package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.android.internal.R;
import libcore.util.Objects;

import java.io.PrintWriter;
import java.util.ArrayList;

final class FillUi {
    private static final String TAG = "FillUi";

    private static final int VISIBLE_OPTIONS_MAX_COUNT = 3;

    interface Callback {
        void onResponsePicked(@NonNull FillResponse response);
        void onDatasetPicked(@NonNull Dataset dataset);
        void onCanceled();
        void onDestroy();
    }

    private final Rect mAnchorBounds = new Rect();

    private final @NonNull AnchoredWindow mWindow;

    private final @NonNull Callback mCallback;

    private final @NonNull ListView mListView;

    private final @Nullable ArrayAdapter<ViewItem> mAdapter;

    private @Nullable String mFilterText;
    private final String mAccessibilityTitle;

    private int mContentWidth;
    private int mContentHeight;

    private boolean mDestroyed;

    FillUi(@NonNull Context context, @NonNull FillResponse response,
            @NonNull AutofillId focusedViewId, @NonNull IBinder windowToken,
            @NonNull Rect anchorBounds, @Nullable String filterText,
            @NonNull Callback callback) {
        mAnchorBounds.set(anchorBounds);
        mCallback = callback;

        mAccessibilityTitle = context.getString(R.string.autofill_picker_accessibility_title);

        if (response.getAuthentication() != null) {
            mListView = null;
            mAdapter = null;

            final View content;
            try {
                content = response.getPresentation().apply(context, null);
            } catch (RuntimeException e) {
                callback.onCanceled();
                Slog.e(TAG, "Error inflating remote views", e);
                mWindow = null;
                return;
            }
            final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
            final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
            content.measure(widthMeasureSpec, heightMeasureSpec);
            content.setOnClickListener(v -> mCallback.onResponsePicked(response));
            mContentWidth = content.getMeasuredWidth();
            mContentHeight = content.getMeasuredHeight();

            mWindow = new AnchoredWindow(windowToken, content);
            mWindow.show(mContentWidth, mContentHeight, mAnchorBounds);
        } else {
            final int datasetCount = response.getDatasets().size();
            final ArrayList<ViewItem> items = new ArrayList<>(datasetCount);
            for (int i = 0; i < datasetCount; i++) {
                final Dataset dataset = response.getDatasets().get(i);
                final int index = dataset.getFieldIds().indexOf(focusedViewId);
                if (index >= 0) {
                    final AutofillValue value = dataset.getFieldValues().get(index);
                    final View view;
                    try {
                        view = dataset.getPresentation().apply(context, null);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "Error inflating remote views", e);
                        continue;
                    }

                    String valueText = null;
                    if (value.isText()) {
                        valueText = value.getTextValue().toString().toLowerCase();
                    }

                    items.add(new ViewItem(dataset, valueText, view));
                }
            }

            mAdapter = new ArrayAdapter<ViewItem>(context, 0, items) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    return getItem(position).getView();
                }
            };

            final LayoutInflater inflater = LayoutInflater.from(context);
            mListView = (ListView) inflater.inflate(
                    com.android.internal.R.layout.autofill_dataset_picker, null);
            mListView.setAdapter(mAdapter);
            mListView.setOnItemClickListener((adapter, view, position, id) -> {
                final ViewItem vi = mAdapter.getItem(position);
                mCallback.onDatasetPicked(vi.getDataset());
            });

            if (filterText == null) {
                mFilterText = null;
            } else {
                mFilterText = filterText.toLowerCase();
            }

            applyNewFilterText();
            mWindow = new AnchoredWindow(windowToken, mListView);
        }
    }

    public void update(@NonNull Rect anchorBounds) {
        throwIfDestroyed();
        if (!mAnchorBounds.equals(anchorBounds)) {
            mAnchorBounds.set(anchorBounds);
            mWindow.show(mContentWidth, mContentHeight, anchorBounds);
        }
    }

    private void applyNewFilterText() {
        mAdapter.getFilter().filter(mFilterText, (count) -> {
            if (mDestroyed) {
                return;
            }
            if (count <= 0) {
                mWindow.hide();
            } else {
                if (updateContentSize()) {
                    mWindow.show(mContentWidth, mContentHeight, mAnchorBounds);
                }
                if (mAdapter.getCount() > VISIBLE_OPTIONS_MAX_COUNT) {
                    mListView.setVerticalScrollBarEnabled(true);
                    mListView.onVisibilityAggregated(true);
                } else {
                    mListView.setVerticalScrollBarEnabled(false);
                }
            }
        });
    }

    public void setFilterText(@Nullable String filterText) {
        throwIfDestroyed();
        if (mAdapter == null) {
            return;
        }

        if (filterText == null) {
            filterText = null;
        } else {
            filterText = filterText.toLowerCase();
        }

        if (Objects.equal(mFilterText, filterText)) {
            return;
        }
        mFilterText = filterText;

        applyNewFilterText();
    }

    public void destroy() {
        throwIfDestroyed();
        mCallback.onDestroy();
        mWindow.hide();
        mDestroyed = true;
    }

    private boolean updateContentSize() {
        if (mAdapter == null) {
            return false;
        }
        boolean changed = false;
        if (mAdapter.getCount() <= 0) {
            if (mContentWidth != 0) {
                mContentWidth = 0;
                changed = true;
            }
            if (mContentHeight != 0) {
                mContentHeight = 0;
                changed = true;
            }
            return changed;
        }

        mContentWidth = 0;
        mContentHeight = 0;

        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
        final int itemCount = Math.min(mAdapter.getCount(), VISIBLE_OPTIONS_MAX_COUNT);
        for (int i = 0; i < itemCount; i++) {
            View view = mAdapter.getItem(i).getView();
            view.measure(widthMeasureSpec, heightMeasureSpec);
            final int newContentWidth = Math.max(mContentWidth, view.getMeasuredWidth());
            if (newContentWidth != mContentWidth) {
                mContentWidth = newContentWidth;
                changed = true;
            }
            final int newContentHeight = mContentHeight + view.getMeasuredHeight();
            if (newContentHeight != mContentHeight) {
                mContentHeight = newContentHeight;
                changed = true;
            }
        }
        return changed;
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    private static class ViewItem {
        private final String mValue;
        private final Dataset mDataset;
        private final View mView;

        ViewItem(Dataset dataset, String value, View view) {
            mDataset = dataset;
            mValue = value;
            mView = view;
        }

        public View getView() {
            return mView;
        }

        public Dataset getDataset() {
            return mDataset;
        }

        @Override
        public String toString() {
            // Used for filtering in the adapter
            return mValue;
        }
    }

    final class AnchoredWindow implements View.OnTouchListener {
        private final Point mTempPoint = new Point();

        private final WindowManager mWm;

        private final IBinder mActivityToken;
        private final View mContentView;

        /**
         * Constructor.
         *
         * @param activityToken token to pass to window manager
         * @param contentView content of the window
         */
        AnchoredWindow(IBinder activityToken, View contentView) {
            mWm = contentView.getContext().getSystemService(WindowManager.class);
            mActivityToken = activityToken;
            mContentView = contentView;
        }

        /**
         * Hides the window.
         */
        void hide() {
            if (mContentView.isAttachedToWindow()) {
                mContentView.setOnTouchListener(null);
                mWm.removeView(mContentView);
            }
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            // When the window is touched outside, hide the window.
            if (view == mContentView && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                mCallback.onCanceled();
                return true;
            }
            return false;
        }

        public void show(int desiredWidth, int desiredHeight, Rect anchorBounds) {
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams();

            params.setTitle("FillUi");
            params.token = mActivityToken;
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            params.accessibilityTitle = mAccessibilityTitle;

            mWm.getDefaultDisplay().getRealSize(mTempPoint);
            final int screenWidth = mTempPoint.x;
            final int screenHeight = mTempPoint.y;

            // Try to place the window at the start of the anchor view if
            // there is space to fit the content, otherwise fit as much of
            // the window as possible moving it to the left using all available
            // screen width.
            params.x = Math.min(anchorBounds.left, Math.max(screenWidth - desiredWidth, 0));
            params.width = Math.min(screenWidth, desiredWidth);

            // Try to fit below using all available space with top-start gravity
            // and if that fails try to fit above using all available space with
            // bottom-start gravity.
            final int verticalSpaceBelow = screenHeight - anchorBounds.bottom;
            if (desiredHeight <= verticalSpaceBelow) {
                // Fits below bounds.
                params.height = desiredHeight;
                params.gravity = Gravity.TOP | Gravity.START;
                params.y = anchorBounds.bottom;
            } else {
                final int verticalSpaceAbove = anchorBounds.top;
                if (desiredHeight <= verticalSpaceAbove) {
                    // Fits above bounds.
                    params.height = desiredHeight;
                    params.gravity = Gravity.BOTTOM | Gravity.START;
                    params.y = anchorBounds.top + desiredHeight;
                } else {
                    // Pick above/below based on which has the most space.
                    if (verticalSpaceBelow >= verticalSpaceAbove) {
                        params.height = verticalSpaceBelow;
                        params.gravity = Gravity.TOP | Gravity.START;
                        params.y = anchorBounds.bottom;
                    } else {
                        params.height = verticalSpaceAbove;
                        params.gravity = Gravity.BOTTOM | Gravity.START;
                        params.y = anchorBounds.top + desiredHeight;
                    }
                }
            }

            if (!mContentView.isAttachedToWindow()) {
                mWm.addView(mContentView, params);
                mContentView.setOnTouchListener(this);
            } else {
                mWm.updateViewLayout(mContentView, params);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mAnchorBounds: "); pw.println(mAnchorBounds);
        pw.print(prefix); pw.print("mCallback: "); pw.println(mCallback != null);
        pw.print(prefix); pw.print("mListView: "); pw.println(mListView);
        pw.print(prefix); pw.print("mAdapter: "); pw.println(mAdapter != null);
        pw.print(prefix); pw.print("mFilterText: "); pw.println(mFilterText);
        pw.print(prefix); pw.print("mAccessibilityTitle: "); pw.println(mAccessibilityTitle);
        pw.print(prefix); pw.print("mContentWidth: "); pw.println(mContentWidth);
        pw.print(prefix); pw.print("mContentHeight: "); pw.println(mContentHeight);
        pw.print(prefix); pw.print("mDestroyed: "); pw.println(mDestroyed);
    }
}
