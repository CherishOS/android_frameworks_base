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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.app.Notification;
import android.content.Context;
import android.os.AsyncTask;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationContentView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * A utility that inflates the right kind of contentView based on the state
 */
public class NotificationInflater {

    @VisibleForTesting
    static final int FLAG_REINFLATE_ALL = ~0;
    private static final int FLAG_REINFLATE_CONTENT_VIEW = 1<<0;
    @VisibleForTesting
    static final int FLAG_REINFLATE_EXPANDED_VIEW = 1<<1;
    private static final int FLAG_REINFLATE_HEADS_UP_VIEW = 1<<2;
    private static final int FLAG_REINFLATE_PUBLIC_VIEW = 1<<3;
    private static final int FLAG_REINFLATE_AMBIENT_VIEW = 1<<4;

    private final ExpandableNotificationRow mRow;
    private boolean mIsLowPriority;
    private boolean mUsesIncreasedHeight;
    private boolean mUsesIncreasedHeadsUpHeight;
    private RemoteViews.OnClickHandler mRemoteViewClickHandler;
    private boolean mIsChildInGroup;
    private InflationCallback mCallback;
    private boolean mRedactAmbient;

    public NotificationInflater(ExpandableNotificationRow row) {
        mRow = row;
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }

    /**
     * Set whether the notification is a child in a group
     *
     * @return whether the view was re-inflated
     */
    public void setIsChildInGroup(boolean childInGroup) {
        if (childInGroup != mIsChildInGroup) {
            mIsChildInGroup = childInGroup;
            if (mIsLowPriority) {
                int flags = FLAG_REINFLATE_CONTENT_VIEW | FLAG_REINFLATE_EXPANDED_VIEW;
                inflateNotificationViews(flags);
            }
        } ;
    }

    public void setUsesIncreasedHeight(boolean usesIncreasedHeight) {
        mUsesIncreasedHeight = usesIncreasedHeight;
    }

    public void setUsesIncreasedHeadsUpHeight(boolean usesIncreasedHeight) {
        mUsesIncreasedHeadsUpHeight = usesIncreasedHeight;
    }

    public void setRemoteViewClickHandler(RemoteViews.OnClickHandler remoteViewClickHandler) {
        mRemoteViewClickHandler = remoteViewClickHandler;
    }

    public void setRedactAmbient(boolean redactAmbient) {
        if (mRedactAmbient != redactAmbient) {
            mRedactAmbient = redactAmbient;
            if (mRow.getEntry() == null) {
                return;
            }
            inflateNotificationViews(FLAG_REINFLATE_AMBIENT_VIEW);
        }
    }

    /**
     * Inflate all views of this notification on a background thread. This is asynchronous and will
     * notify the callback once it's finished.
     */
    public void inflateNotificationViews() {
        inflateNotificationViews(FLAG_REINFLATE_ALL);
    }

    /**
     * Reinflate all views for the specified flags on a background thread. This is asynchronous and
     * will notify the callback once it's finished.
     *
     * @param reInflateFlags flags which views should be reinflated. Use {@link #FLAG_REINFLATE_ALL}
     *                       to reinflate all of views.
     */
    @VisibleForTesting
    void inflateNotificationViews(int reInflateFlags) {
        StatusBarNotification sbn = mRow.getEntry().notification;
        new AsyncInflationTask(mRow.getContext(), sbn, reInflateFlags).execute();
    }

    @VisibleForTesting
    void inflateNotificationViews(int reInflateFlags,
            Notification.Builder builder, Context packageContext) {
        NotificationData.Entry entry = mRow.getEntry();
        NotificationContentView privateLayout = mRow.getPrivateLayout();
        NotificationContentView publicLayout = mRow.getPublicLayout();

        boolean isLowPriority = mIsLowPriority && !mIsChildInGroup;
        if ((reInflateFlags & FLAG_REINFLATE_CONTENT_VIEW) != 0) {
            final RemoteViews newContentView = createContentView(builder,
                    isLowPriority, mUsesIncreasedHeight);
            if (!compareRemoteViews(newContentView,
                    entry.cachedContentView)) {
                View contentViewLocal = newContentView.apply(
                        packageContext,
                        privateLayout,
                        mRemoteViewClickHandler);
                contentViewLocal.setIsRootNamespace(true);
                privateLayout.setContractedChild(contentViewLocal);
            } else {
                newContentView.reapply(packageContext,
                        privateLayout.getContractedChild(),
                        mRemoteViewClickHandler);
            }
            entry.cachedContentView = newContentView;
        }

        if ((reInflateFlags & FLAG_REINFLATE_EXPANDED_VIEW) != 0) {
            final RemoteViews newBigContentView = createBigContentView(
                    builder, isLowPriority);
            if (newBigContentView != null) {
                if (!compareRemoteViews(newBigContentView, entry.cachedBigContentView)) {
                    View bigContentViewLocal = newBigContentView.apply(
                            packageContext,
                            privateLayout,
                            mRemoteViewClickHandler);
                    bigContentViewLocal.setIsRootNamespace(true);
                    privateLayout.setExpandedChild(bigContentViewLocal);
                } else {
                    newBigContentView.reapply(packageContext,
                            privateLayout.getExpandedChild(),
                            mRemoteViewClickHandler);
                }
            } else if (entry.cachedBigContentView != null) {
                privateLayout.setExpandedChild(null);
            }
            entry.cachedBigContentView = newBigContentView;
            mRow.setExpandable(newBigContentView != null);
        }

        if ((reInflateFlags & FLAG_REINFLATE_HEADS_UP_VIEW) != 0) {
            final RemoteViews newHeadsUpContentView =
                    builder.createHeadsUpContentView(mUsesIncreasedHeadsUpHeight);
            if (newHeadsUpContentView != null) {
                if (!compareRemoteViews(newHeadsUpContentView,
                        entry.cachedHeadsUpContentView)) {
                    View headsUpContentViewLocal = newHeadsUpContentView.apply(
                            packageContext,
                            privateLayout,
                            mRemoteViewClickHandler);
                    headsUpContentViewLocal.setIsRootNamespace(true);
                    privateLayout.setHeadsUpChild(headsUpContentViewLocal);
                } else {
                    newHeadsUpContentView.reapply(packageContext,
                            privateLayout.getHeadsUpChild(),
                            mRemoteViewClickHandler);
                }
            } else if (entry.cachedHeadsUpContentView != null) {
                privateLayout.setHeadsUpChild(null);
            }
            entry.cachedHeadsUpContentView = newHeadsUpContentView;
        }

        if ((reInflateFlags & FLAG_REINFLATE_PUBLIC_VIEW) != 0) {
            final RemoteViews newPublicNotification
                    = builder.makePublicContentView();
            if (!compareRemoteViews(newPublicNotification, entry.cachedPublicContentView)) {
                View publicContentView = newPublicNotification.apply(
                        packageContext,
                        publicLayout,
                        mRemoteViewClickHandler);
                publicContentView.setIsRootNamespace(true);
                publicLayout.setContractedChild(publicContentView);
            } else {
                newPublicNotification.reapply(packageContext,
                        publicLayout.getContractedChild(),
                        mRemoteViewClickHandler);
            }
            entry.cachedPublicContentView = newPublicNotification;
        }

        if ((reInflateFlags & FLAG_REINFLATE_AMBIENT_VIEW) != 0) {
            final RemoteViews newAmbientNotification = mRedactAmbient
                    ? builder.makePublicAmbientNotification()
                    : builder.makeAmbientNotification();
            NotificationContentView newParent = mRedactAmbient ? publicLayout : privateLayout;
            NotificationContentView otherParent = !mRedactAmbient ? publicLayout : privateLayout;

            if (newParent.getAmbientChild() == null ||
                    !compareRemoteViews(newAmbientNotification, entry.cachedAmbientContentView)) {
                View ambientContentView = newAmbientNotification.apply(
                        packageContext,
                        newParent,
                        mRemoteViewClickHandler);
                ambientContentView.setIsRootNamespace(true);
                newParent.setAmbientChild(ambientContentView);
                otherParent.setAmbientChild(null);
            } else {
                newAmbientNotification.reapply(packageContext,
                        newParent.getAmbientChild(),
                        mRemoteViewClickHandler);
            }
            entry.cachedAmbientContentView = newAmbientNotification;
        }
    }

    private RemoteViews createBigContentView(Notification.Builder builder,
            boolean isLowPriority) {
        RemoteViews bigContentView = builder.createBigContentView();
        if (bigContentView != null) {
            return bigContentView;
        }
        if (isLowPriority) {
            RemoteViews contentView = builder.createContentView();
            Notification.Builder.makeHeaderExpanded(contentView);
            return contentView;
        }
        return null;
    }

    private RemoteViews createContentView(Notification.Builder builder,
            boolean isLowPriority, boolean useLarge) {
        if (isLowPriority) {
            return builder.makeLowPriorityContentView(false /* useRegularSubtext */);
        }
        return builder.createContentView(useLarge);
    }

    // Returns true if the RemoteViews are the same.
    private boolean compareRemoteViews(final RemoteViews a, final RemoteViews b) {
        return (a == null && b == null) ||
                (a != null && b != null
                        && b.getPackage() != null
                        && a.getPackage() != null
                        && a.getPackage().equals(b.getPackage())
                        && a.getLayoutId() == b.getLayoutId());
    }

    public void setInflationCallback(InflationCallback callback) {
        mCallback = callback;
    }

    public interface InflationCallback {
        void handleInflationException(StatusBarNotification notification, InflationException e);
        void onAsyncInflationFinished(NotificationData.Entry entry);
    }

    public void onDensityOrFontScaleChanged() {
        NotificationData.Entry entry = mRow.getEntry();
        entry.cachedAmbientContentView = null;
        entry.cachedBigContentView = null;
        entry.cachedContentView = null;
        entry.cachedHeadsUpContentView = null;
        entry.cachedPublicContentView = null;
        inflateNotificationViews();
    }

    private class AsyncInflationTask extends AsyncTask<Void, Void, Notification.Builder> {

        private final StatusBarNotification mSbn;
        private final Context mContext;
        private final int mReInflateFlags;
        private Context mPackageContext = null;
        private Exception mError;

        private AsyncInflationTask(Context context, StatusBarNotification notification,
                int reInflateFlags) {
            mSbn = notification;
            mContext = context;
            mReInflateFlags = reInflateFlags;
            mRow.getEntry().addInflationTask(this);
        }

        @Override
        protected Notification.Builder doInBackground(Void... params) {
            try {
                final Notification.Builder recoveredBuilder
                        = Notification.Builder.recoverBuilder(mContext,
                        mSbn.getNotification());
                mPackageContext = mSbn.getPackageContext(mContext);
                Notification notification = mSbn.getNotification();
                if (notification.isMediaNotification()) {
                    MediaNotificationProcessor processor = new MediaNotificationProcessor(mContext,
                            mPackageContext);
                    processor.setIsLowPriority(mIsLowPriority);
                    processor.processNotification(notification, recoveredBuilder);
                }
                return recoveredBuilder;
            } catch (Exception e) {
                mError = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(Notification.Builder builder) {
            mRow.getEntry().onInflationTaskFinished(this);
            if (mError == null) {
                finishInflation(mReInflateFlags, builder, mPackageContext);
            } else {
                handleError(mError);
            }
        }
    }

    private void finishInflation(int reinflationFlags, Notification.Builder builder,
            Context context) {
        try {
            inflateNotificationViews(reinflationFlags, builder, context);
        } catch (RuntimeException e){
            handleError(e);
            return;
        }
        mRow.onNotificationUpdated();
        mCallback.onAsyncInflationFinished(mRow.getEntry());
    }

    private void handleError(Exception e) {
        StatusBarNotification sbn = mRow.getStatusBarNotification();
        final String ident = sbn.getPackageName() + "/0x"
                + Integer.toHexString(sbn.getId());
        Log.e(StatusBar.TAG, "couldn't inflate view for notification " + ident, e);
        mCallback.handleInflationException(sbn,
                new InflationException("Couldn't inflate contentViews" + e));
    }
}
