/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.Gefingerpoken;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.NotificationTapHelper;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link ActivatableNotificationView}
 */
public class ActivatableNotificationViewController
        extends ViewController<ActivatableNotificationView> {
    private final ExpandableOutlineViewController mExpandableOutlineViewController;
    private final AccessibilityManager mAccessibilityManager;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;
    private final NotificationTapHelper mNotificationTapHelper;
    private final TouchHandler mTouchHandler = new TouchHandler();

    private boolean mNeedsDimming;

    @Inject
    public ActivatableNotificationViewController(ActivatableNotificationView view,
            NotificationTapHelper.Factory notificationTapHelpFactory,
            ExpandableOutlineViewController expandableOutlineViewController,
            AccessibilityManager accessibilityManager, FalsingManager falsingManager,
            FalsingCollector falsingCollector) {
        super(view);
        mExpandableOutlineViewController = expandableOutlineViewController;
        mAccessibilityManager = accessibilityManager;
        mFalsingManager = falsingManager;
        mFalsingCollector = falsingCollector;

        mNotificationTapHelper = notificationTapHelpFactory.create(
                (active) -> {
                    if (active) {
                        mView.makeActive();
                        mFalsingCollector.onNotificationActive();
                    } else {
                        mView.makeInactive(true /* animate */);
                    }
                }, mView::performClick, mView::handleSlideBack);

        mView.setOnActivatedListener(new ActivatableNotificationView.OnActivatedListener() {
            @Override
            public void onActivated(ActivatableNotificationView view) {
                mFalsingCollector.onNotificationActive();
            }

            @Override
            public void onActivationReset(ActivatableNotificationView view) {
            }
        });
    }

    /**
     * Initialize the controller, setting up handlers and other behavior.
     */
    @Override
    public void onInit() {
        mExpandableOutlineViewController.init();
        mView.setOnTouchListener(mTouchHandler);
        mView.setTouchHandler(mTouchHandler);
        mView.setOnDimmedListener(dimmed -> mNeedsDimming = dimmed);
        mView.setAccessibilityManager(mAccessibilityManager);
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }

    class TouchHandler implements Gefingerpoken, View.OnTouchListener {
        private boolean mBlockNextTouch;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            boolean result;
            if (mBlockNextTouch) {
                mBlockNextTouch = false;
                return true;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                mView.setLastActionUpTime(SystemClock.uptimeMillis());
            }
            if (mNeedsDimming && !mAccessibilityManager.isTouchExplorationEnabled()
                    && mView.isInteractive()) {
                if (mNeedsDimming && !mView.isDimmed()) {
                    // We're actually dimmed, but our content isn't dimmable,
                    // let's ensure we have a ripple
                    return false;
                }
                result = mNotificationTapHelper.onTouchEvent(ev, mView.getActualHeight());
            } else {
                return false;
            }
            return result;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (mNeedsDimming && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                    && mView.disallowSingleClick(ev)
                    && !mAccessibilityManager.isTouchExplorationEnabled()) {
                if (!mView.isActive()) {
                    return true;
                } else if (mFalsingManager.isFalseDoubleTap()) {
                    mBlockNextTouch = true;
                    mView.makeInactive(true /* animate */);
                    return true;
                }
            }
            return false;
        }

        /**
         * Use {@link #onTouch(View, MotionEvent) instead}.
         */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }
    }
}
