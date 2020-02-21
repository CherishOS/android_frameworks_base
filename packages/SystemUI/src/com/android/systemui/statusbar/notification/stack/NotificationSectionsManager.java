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

package com.android.systemui.statusbar.notification.stack;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.people.DataListener;
import com.android.systemui.statusbar.notification.people.PeopleHubViewAdapter;
import com.android.systemui.statusbar.notification.people.PeopleHubViewBoundary;
import com.android.systemui.statusbar.notification.people.PersonViewModel;
import com.android.systemui.statusbar.notification.people.Subscription;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import kotlin.sequences.Sequence;

/**
 * Manages the boundaries of the two notification sections (high priority and low priority). Also
 * shows/hides the headers for those sections where appropriate.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
public class NotificationSectionsManager implements StackScrollAlgorithm.SectionProvider {

    private static final String TAG = "NotifSectionsManager";
    private static final boolean DEBUG = false;

    private final ActivityStarter mActivityStarter;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final PeopleHubViewAdapter mPeopleHubViewAdapter;
    private final NotificationSectionsFeatureManager mSectionsFeatureManager;
    private final int mNumberOfSections;

    private final PeopleHubViewBoundary mPeopleHubViewBoundary = new PeopleHubViewBoundary() {
        @Override
        public void setVisible(boolean isVisible) {
            if (mPeopleHubVisible != isVisible) {
                mPeopleHubVisible = isVisible;
                if (mInitialized) {
                    updateSectionBoundaries();
                }
            }
        }

        @NonNull
        @Override
        public View getAssociatedViewForClickAnimation() {
            return mPeopleHubView;
        }

        @NonNull
        @Override
        public Sequence<DataListener<PersonViewModel>> getPersonViewAdapters() {
            return mPeopleHubView.getPersonViewAdapters();
        }
    };

    private NotificationStackScrollLayout mParent;
    private boolean mInitialized = false;

    private SectionHeaderView mGentleHeader;
    private boolean mGentleHeaderVisible;
    @Nullable private View.OnClickListener mOnClearGentleNotifsClickListener;

    private SectionHeaderView mAlertingHeader;
    private boolean mAlertingHeaderVisible;

    private PeopleHubView mPeopleHubView;
    private boolean mPeopleHeaderVisible;
    private boolean mPeopleHubVisible = false;
    @Nullable private Subscription mPeopleHubSubscription;

    @Inject
    NotificationSectionsManager(
            ActivityStarter activityStarter,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            PeopleHubViewAdapter peopleHubViewAdapter,
            NotificationSectionsFeatureManager sectionsFeatureManager) {
        mActivityStarter = activityStarter;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;
        mPeopleHubViewAdapter = peopleHubViewAdapter;
        mSectionsFeatureManager = sectionsFeatureManager;
        mNumberOfSections = mSectionsFeatureManager.getNumberOfBuckets();
    }

    NotificationSection[] createSectionsForBuckets() {
        int[] buckets = mSectionsFeatureManager.getNotificationBuckets();
        NotificationSection[] sections = new NotificationSection[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            sections[i] = new NotificationSection(mParent, buckets[i] /* bucket */);
        }

        return sections;
    }

    /** Must be called before use. */
    void initialize(
            NotificationStackScrollLayout parent, LayoutInflater layoutInflater) {
        if (mInitialized) {
            throw new IllegalStateException("NotificationSectionsManager already initialized");
        }
        mInitialized = true;
        mParent = parent;
        reinflateViews(layoutInflater);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    private <T extends ExpandableView> T reinflateView(
            T view, LayoutInflater layoutInflater, @LayoutRes int layoutResId) {
        int oldPos = -1;
        if (view != null) {
            if (view.getTransientContainer() != null) {
                view.getTransientContainer().removeView(mGentleHeader);
            } else if (view.getParent() != null) {
                oldPos = mParent.indexOfChild(view);
                mParent.removeView(view);
            }
        }

        view = (T) layoutInflater.inflate(layoutResId, mParent, false);

        if (oldPos != -1) {
            mParent.addView(view, oldPos);
        }

        return view;
    }

    /**
     * Reinflates the entire notification header, including all decoration views.
     */
    void reinflateViews(LayoutInflater layoutInflater) {
        mGentleHeader = reinflateView(
                mGentleHeader, layoutInflater, R.layout.status_bar_notification_section_header);
        mGentleHeader.setHeaderText(R.string.notification_section_header_gentle);
        mGentleHeader.setOnHeaderClickListener(this::onGentleHeaderClick);
        mGentleHeader.setOnClearAllClickListener(this::onClearGentleNotifsClick);

        mAlertingHeader = reinflateView(
                mAlertingHeader, layoutInflater, R.layout.status_bar_notification_section_header);
        mAlertingHeader.setHeaderText(R.string.notification_section_header_alerting);
        mAlertingHeader.setOnHeaderClickListener(this::onGentleHeaderClick);

        if (mPeopleHubSubscription != null) {
            mPeopleHubSubscription.unsubscribe();
        }
        mPeopleHubView = reinflateView(mPeopleHubView, layoutInflater, R.layout.people_strip);
        mPeopleHubSubscription = mPeopleHubViewAdapter.bindView(mPeopleHubViewBoundary);
    }

    /** Listener for when the "clear all" button is clicked on the gentle notification header. */
    void setOnClearGentleNotifsClickListener(View.OnClickListener listener) {
        mOnClearGentleNotifsClickListener = listener;
    }

    @Override
    public boolean beginsSection(@NonNull View view, @Nullable View previous) {
        return view == mGentleHeader
                || view == mPeopleHubView
                || view == mAlertingHeader
                || !Objects.equals(getBucket(view), getBucket(previous));
    }

    private boolean isUsingMultipleSections() {
        return mNumberOfSections > 1;
    }

    @Nullable
    private Integer getBucket(View view) {
        if (view == mGentleHeader) {
            return BUCKET_SILENT;
        } else if (view == mPeopleHubView) {
            return BUCKET_PEOPLE;
        } else if (view == mAlertingHeader) {
            return BUCKET_ALERTING;
        } else if (view instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) view).getEntry().getBucket();
        }
        return null;
    }

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    void updateSectionBoundaries() {
        if (!isUsingMultipleSections()) {
            return;
        }

        final boolean showHeaders = mStatusBarStateController.getState() != StatusBarState.KEYGUARD;
        final boolean usingPeopleFiltering = mSectionsFeatureManager.isFilteringEnabled();

        boolean peopleNotifsPresent = false;
        int peopleHeaderTarget = -1;
        int alertingHeaderTarget = -1;
        int gentleHeaderTarget = -1;

        int viewCount = 0;

        if (showHeaders) {
            final int childCount = mParent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = mParent.getChildAt(i);
                if (child.getVisibility() == View.GONE
                        || !(child instanceof ExpandableNotificationRow)) {
                    continue;
                }
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                switch (row.getEntry().getBucket()) {
                    case BUCKET_PEOPLE:
                        if (peopleHeaderTarget == -1) {
                            peopleNotifsPresent = true;
                            peopleHeaderTarget = viewCount;
                            viewCount++;
                        }
                        break;
                    case BUCKET_ALERTING:
                        if (usingPeopleFiltering && alertingHeaderTarget == -1) {
                            alertingHeaderTarget = viewCount;
                            viewCount++;
                        }
                        break;
                    case BUCKET_SILENT:
                        if (gentleHeaderTarget == -1) {
                            gentleHeaderTarget = viewCount;
                            viewCount++;
                        }
                        break;
                }
                viewCount++;
            }
            if (usingPeopleFiltering && mPeopleHubVisible && peopleHeaderTarget == -1) {
                // Insert the people header even if there are no people visible, in order to show
                // the hub. Put it directly above the next header.
                if (alertingHeaderTarget != -1) {
                    peopleHeaderTarget = alertingHeaderTarget;
                    alertingHeaderTarget++;
                    gentleHeaderTarget++;
                } else if (gentleHeaderTarget != -1) {
                    peopleHeaderTarget = gentleHeaderTarget;
                    gentleHeaderTarget++;
                } else {
                    // Put it at the end of the list.
                    peopleHeaderTarget = viewCount;
                }
            }
        }

        // Allow swiping the people header if the section is empty
        mPeopleHubView.setCanSwipe(mPeopleHubVisible && !peopleNotifsPresent);

        mPeopleHeaderVisible = adjustHeaderVisibilityAndPosition(
                peopleHeaderTarget, mPeopleHubView, mPeopleHeaderVisible);
        mAlertingHeaderVisible = adjustHeaderVisibilityAndPosition(
                alertingHeaderTarget, mAlertingHeader, mAlertingHeaderVisible);
        mGentleHeaderVisible = adjustHeaderVisibilityAndPosition(
                gentleHeaderTarget, mGentleHeader, mGentleHeaderVisible);
    }

    private boolean adjustHeaderVisibilityAndPosition(
            int targetIndex, StackScrollerDecorView header, boolean isCurrentlyVisible) {
        if (targetIndex == -1) {
            if (isCurrentlyVisible) {
                mParent.removeView(header);
            }
            return false;
        } else {
            if (header instanceof SwipeableView) {
                ((SwipeableView) header).resetTranslation();
            }
            if (!isCurrentlyVisible) {
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                if (header.getTransientContainer() != null) {
                    header.getTransientContainer().removeTransientView(header);
                    header.setTransientContainer(null);
                }
                header.setContentVisible(true);
                mParent.addView(header, targetIndex);
            } else if (mParent.indexOfChild(header) != targetIndex) {
                mParent.changeViewPosition(header, targetIndex);
            }
            return true;
        }
    }

    /**
     * Updates the boundaries (as tracked by their first and last views) of the priority sections.
     *
     * @return {@code true} If the last view in the top section changed (so we need to animate).
     */
    boolean updateFirstAndLastViewsForAllSections(
            NotificationSection[] sections,
            List<ActivatableNotificationView> children) {

        if (sections.length <= 0 || children.size() <= 0) {
            for (NotificationSection s : sections) {
                s.setFirstVisibleChild(null);
                s.setLastVisibleChild(null);
            }
            return false;
        }

        boolean changed = false;
        ArrayList<ActivatableNotificationView> viewsInBucket = new ArrayList<>();
        for (NotificationSection s : sections) {
            int filter = s.getBucket();
            viewsInBucket.clear();

            //TODO: do this in a single pass, and more better
            for (ActivatableNotificationView v : children)  {
                Integer bucket = getBucket(v);
                if (bucket == null) {
                    throw new IllegalArgumentException("Cannot find section bucket for view");
                }

                if (bucket == filter) {
                    viewsInBucket.add(v);
                }

                if (viewsInBucket.size() >= 1) {
                    changed |= s.setFirstVisibleChild(viewsInBucket.get(0));
                    changed |= s.setLastVisibleChild(viewsInBucket.get(viewsInBucket.size() - 1));
                } else {
                    changed |= s.setFirstVisibleChild(null);
                    changed |= s.setLastVisibleChild(null);
                }
            }
        }

        if (DEBUG) {
            logSections(sections);
        }

        return changed;
    }

    private void logSections(NotificationSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            NotificationSection s = sections[i];
            ActivatableNotificationView first = s.getFirstVisibleChild();
            String fs = first == null ? "(null)"
                    :  (first instanceof ExpandableNotificationRow)
                            ? ((ExpandableNotificationRow) first).getEntry().getKey()
                            : Integer.toHexString(System.identityHashCode(first));
            ActivatableNotificationView last = s.getLastVisibleChild();
            String ls = last == null ? "(null)"
                    :  (last instanceof ExpandableNotificationRow)
                            ? ((ExpandableNotificationRow) last).getEntry().getKey()
                            : Integer.toHexString(System.identityHashCode(last));
            android.util.Log.d(TAG, "updateSections: f=" + fs + " s=" + i);
            android.util.Log.d(TAG, "updateSections: l=" + ls + " s=" + i);
        }
    }


    @VisibleForTesting
    SectionHeaderView getGentleHeaderView() {
        return mGentleHeader;
    }

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            mGentleHeader.reinflateContents();
        }
    };

    private void onGentleHeaderClick(View v) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_SETTINGS);
        mActivityStarter.startActivity(
                intent,
                true,
                true,
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void onClearGentleNotifsClick(View v) {
        if (mOnClearGentleNotifsClickListener != null) {
            mOnClearGentleNotifsClickListener.onClick(v);
        }
    }

    void hidePeopleRow() {
        mPeopleHubVisible = false;
        updateSectionBoundaries();
    }

    /**
     * For now, declare the available notification buckets (sections) here so that other
     * presentation code can decide what to do based on an entry's buckets
     */
    @Retention(SOURCE)
    @IntDef(prefix = { "BUCKET_" }, value = {
            BUCKET_HEADS_UP,
            BUCKET_PEOPLE,
            BUCKET_ALERTING,
            BUCKET_SILENT
    })
    public @interface PriorityBucket {}
    public static final int BUCKET_HEADS_UP = 0;
    public static final int BUCKET_PEOPLE = 1;
    public static final int BUCKET_ALERTING = 2;
    public static final int BUCKET_SILENT = 3;
}
