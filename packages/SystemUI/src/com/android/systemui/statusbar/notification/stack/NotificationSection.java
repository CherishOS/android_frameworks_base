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

package com.android.systemui.statusbar.notification.stack;

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_MEDIA_CONTROLS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.notification.ShadeViewRefactor;
import com.android.systemui.statusbar.notification.row.ExpandableView;

/**
 * Represents the priority of a notification section and tracks first and last visible children.
 */
public class NotificationSection {
    private @PriorityBucket int mBucket;
    private ExpandableView mFirstVisibleChild;
    private ExpandableView mLastVisibleChild;

    NotificationSection(@PriorityBucket int bucket) {
        mBucket = bucket;
    }

    @PriorityBucket
    public int getBucket() {
        return mBucket;
    }

    public ExpandableView getFirstVisibleChild() {
        return mFirstVisibleChild;
    }

    public ExpandableView getLastVisibleChild() {
        return mLastVisibleChild;
    }

    public boolean setFirstVisibleChild(ExpandableView child) {
        boolean changed = mFirstVisibleChild != child;
        mFirstVisibleChild = child;
        return changed;
    }

    public boolean setLastVisibleChild(ExpandableView child) {
        boolean changed = mLastVisibleChild != child;
        mLastVisibleChild = child;
        return changed;
    }
}
