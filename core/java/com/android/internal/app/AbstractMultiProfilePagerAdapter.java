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
package com.android.internal.app;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.ViewPager;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Skeletal {@link PagerAdapter} implementation of a work or personal profile page for
 * intent resolution (including share sheet).
 */
public abstract class AbstractMultiProfilePagerAdapter extends PagerAdapter {

    private static final String TAG = "AbstractMultiProfilePagerAdapter";
    static final int PROFILE_PERSONAL = 0;
    static final int PROFILE_WORK = 1;
    @IntDef({PROFILE_PERSONAL, PROFILE_WORK})
    @interface Profile {}

    private final Context mContext;
    private int mCurrentPage;
    private OnProfileSelectedListener mOnProfileSelectedListener;
    private Set<Integer> mLoadedPages;

    AbstractMultiProfilePagerAdapter(Context context, int currentPage) {
        mContext = Objects.requireNonNull(context);
        mCurrentPage = currentPage;
        mLoadedPages = new HashSet<>();
    }

    void setOnProfileSelectedListener(OnProfileSelectedListener listener) {
        mOnProfileSelectedListener = listener;
    }

    Context getContext() {
        return mContext;
    }

    /**
     * Sets this instance of this class as {@link ViewPager}'s {@link PagerAdapter} and sets
     * an {@link ViewPager.OnPageChangeListener} where it keeps track of the currently displayed
     * page and rebuilds the list.
     */
    void setupViewPager(ViewPager viewPager) {
        viewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mCurrentPage = position;
                if (!mLoadedPages.contains(position)) {
                    getActiveListAdapter().rebuildList();
                    mLoadedPages.add(position);
                }
                if (mOnProfileSelectedListener != null) {
                    mOnProfileSelectedListener.onProfileSelected(position);
                }
            }
        });
        viewPager.setAdapter(this);
        viewPager.setCurrentItem(mCurrentPage);
        mLoadedPages.add(mCurrentPage);
    }

    @Override
    public ViewGroup instantiateItem(ViewGroup container, int position) {
        final ProfileDescriptor profileDescriptor = getItem(position);
        setupListAdapter(position);
        container.addView(profileDescriptor.rootView);
        return profileDescriptor.rootView;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object view) {
        container.removeView((View) view);
    }

    @Override
    public int getCount() {
        return getItemCount();
    }

    protected int getCurrentPage() {
        return mCurrentPage;
    }

    @VisibleForTesting
    public UserHandle getCurrentUserHandle() {
        return getActiveListAdapter().mResolverListController.getUserHandle();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return null;
    }

    /**
     * Returns the {@link ProfileDescriptor} relevant to the given <code>pageIndex</code>.
     * <ul>
     * <li>For a device with only one user, <code>pageIndex</code> value of
     * <code>0</code> would return the personal profile {@link ProfileDescriptor}.</li>
     * <li>For a device with a work profile, <code>pageIndex</code> value of <code>0</code> would
     * return the personal profile {@link ProfileDescriptor}, and <code>pageIndex</code> value of
     * <code>1</code> would return the work profile {@link ProfileDescriptor}.</li>
     * </ul>
     */
    abstract ProfileDescriptor getItem(int pageIndex);

    /**
     * Returns the number of {@link ProfileDescriptor} objects.
     * <p>For a normal consumer device with only one user returns <code>1</code>.
     * <p>For a device with a work profile returns <code>2</code>.
     */
    abstract int getItemCount();

    /**
     * Responsible for assigning an adapter to the list view for the relevant page, specified by
     * <code>pageIndex</code>, and other list view-related initialization procedures.
     */
    abstract void setupListAdapter(int pageIndex);

    /**
     * Returns the adapter of the list view for the relevant page specified by
     * <code>pageIndex</code>.
     * <p>This method is meant to be implemented with an implementation-specific return type
     * depending on the adapter type.
     */
    @VisibleForTesting
    public abstract Object getAdapterForIndex(int pageIndex);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that represents
     * <code>userHandle</code>. If there is no such adapter for the specified
     * <code>userHandle</code>, returns {@code null}.
     * <p>For example, if there is a work profile on the device with user id 10, calling this method
     * with <code>UserHandle.of(10)</code> returns the work profile {@link ResolverListAdapter}.
     */
    @Nullable
    abstract ResolverListAdapter getListAdapterForUserHandle(UserHandle userHandle);

    /**
     * Returns the {@link ResolverListAdapter} instance of the profile that is currently visible
     * to the user.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the work profile {@link ResolverListAdapter}.
     * @see #getInactiveListAdapter()
     */
    @VisibleForTesting
    public abstract ResolverListAdapter getActiveListAdapter();

    /**
     * If this is a device with a work profile, returns the {@link ResolverListAdapter} instance
     * of the profile that is <b><i>not</i></b> currently visible to the user. Otherwise returns
     * {@code null}.
     * <p>For example, if the user is viewing the work tab in the share sheet, this method returns
     * the personal profile {@link ResolverListAdapter}.
     * @see #getActiveListAdapter()
     */
    @VisibleForTesting
    public abstract @Nullable ResolverListAdapter getInactiveListAdapter();

    abstract Object getCurrentRootAdapter();

    abstract ViewGroup getActiveAdapterView();

    abstract @Nullable ViewGroup getInactiveAdapterView();

    protected class ProfileDescriptor {
        final ViewGroup rootView;
        ProfileDescriptor(ViewGroup rootView) {
            this.rootView = rootView;
        }
    }

    public interface OnProfileSelectedListener {
        /**
         * Callback for when the user changes the active tab from personal to work or vice versa.
         * <p>This callback is only called when the intent resolver or share sheet shows
         * the work and personal profiles.
         * @param profileIndex {@link #PROFILE_PERSONAL} if the personal profile was selected or
         * {@link #PROFILE_WORK} if the work profile was selected.
         */
        void onProfileSelected(int profileIndex);
    }
}