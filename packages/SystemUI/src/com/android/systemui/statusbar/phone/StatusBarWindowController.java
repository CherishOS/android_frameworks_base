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

package com.android.systemui.statusbar.phone;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.ViewRootImpl.INSETS_LAYOUT_GENERALIZATION;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import javax.inject.Inject;

/**
 * Encapsulates all logic for the status bar window state management.
 */
@SysUISingleton
public class StatusBarWindowController {
    private static final String TAG = "StatusBarWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IWindowManager mIWindowManager;
    private final StatusBarContentInsetsProvider mContentInsetsProvider;
    private final Resources mResources;
    private int mBarHeight = -1;
    private final State mCurrentState = new State();

    private final ViewGroup mStatusBarView;
    private final ViewGroup mLaunchAnimationContainer;
    private WindowManager.LayoutParams mLp;
    private final WindowManager.LayoutParams mLpChanged;

    @Inject
    public StatusBarWindowController(
            Context context,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            StatusBarWindowView statusBarWindowView,
            StatusBarContentInsetsProvider contentInsetsProvider,
            @Main Resources resources) {
        mContext = context;
        mWindowManager = windowManager;
        mIWindowManager = iWindowManager;
        mContentInsetsProvider = contentInsetsProvider;
        mStatusBarView = statusBarWindowView;
        mLaunchAnimationContainer = mStatusBarView.findViewById(
                R.id.status_bar_launch_animation_container);
        mLpChanged = new WindowManager.LayoutParams();
        mResources = resources;

        if (mBarHeight < 0) {
            mBarHeight = mResources.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
        }
    }

    public int getStatusBarHeight() {
        return mBarHeight;
    }

    /**
     * Rereads the status_bar_height from configuration and reapplys the current state if the height
     * is different.
     */
    public void refreshStatusBarHeight() {
        int heightFromConfig = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        if (mBarHeight != heightFromConfig) {
            mBarHeight = heightFromConfig;
            apply(mCurrentState);
        }

        if (DEBUG) Log.v(TAG, "defineSlots");
    }

    /**
     * Adds the status bar view to the window manager.
     */
    public void attach() {
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = getBarLayoutParams(mContext.getDisplay().getRotation());

        mWindowManager.addView(mStatusBarView, mLp);
        mLpChanged.copyFrom(mLp);

        mContentInsetsProvider.addCallback(this::calculateStatusBarLocationsForAllRotations);
        calculateStatusBarLocationsForAllRotations();
    }

    private WindowManager.LayoutParams getBarLayoutParams(int rotation) {
        WindowManager.LayoutParams lp = getBarLayoutParamsForRotation(rotation);
        lp.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            lp.paramsForRotation[rot] = getBarLayoutParamsForRotation(rot);
        }
        return lp;
    }

    private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
        int height = mBarHeight;
        if (INSETS_LAYOUT_GENERALIZATION) {
            Rect displayBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
            int defaultAndUpsideDownHeight;
            int theOtherHeight;
            if (displayBounds.width() > displayBounds.height()) {
                defaultAndUpsideDownHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height_landscape);
                theOtherHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height_portrait);
            } else {
                defaultAndUpsideDownHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height_portrait);
                theOtherHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height_landscape);
            }
            switch (rotation) {
                case ROTATION_UNDEFINED:
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    height = defaultAndUpsideDownHeight;
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    height = theOtherHeight;
                    break;
            }
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        lp.token = new Binder();
        lp.gravity = Gravity.TOP;
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;

    }

    private void calculateStatusBarLocationsForAllRotations() {
        Rect[] bounds = new Rect[4];
        bounds[0] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_NONE);
        bounds[1] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_LANDSCAPE);
        bounds[2] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_UPSIDE_DOWN);
        bounds[3] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_SEASCAPE);

        try {
            mIWindowManager.updateStaticPrivacyIndicatorBounds(mContext.getDisplayId(), bounds);
        } catch (RemoteException e) {
             //Swallow
        }
    }

    /** Set force status bar visible. */
    public void setForceStatusBarVisible(boolean forceStatusBarVisible) {
        mCurrentState.mForceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    /**
     * Return the container in which we should run launch animations started from the status bar and
     * expanding into the opening window.
     *
     * @see #setLaunchAnimationRunning
     */
    public ViewGroup getLaunchAnimationContainer() {
        return mLaunchAnimationContainer;
    }

    /**
     * Set whether a launch animation is currently running. If true, this will ensure that the
     * window matches its parent height so that the animation is not clipped by the normal status
     * bar height.
     */
    public void setLaunchAnimationRunning(boolean isLaunchAnimationRunning) {
        if (isLaunchAnimationRunning == mCurrentState.mIsLaunchAnimationRunning) {
            return;
        }

        mCurrentState.mIsLaunchAnimationRunning = isLaunchAnimationRunning;
        apply(mCurrentState);
    }

    private void applyHeight(State state) {
        mLpChanged.height =
                state.mIsLaunchAnimationRunning ? ViewGroup.LayoutParams.MATCH_PARENT : mBarHeight;
    }

    private void apply(State state) {
        applyForceStatusBarVisibleFlag(state);
        applyHeight(state);
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarView, mLp);
        }
    }

    private static class State {
        boolean mForceStatusBarVisible;
        boolean mIsLaunchAnimationRunning;
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        if (state.mForceStatusBarVisible || state.mIsLaunchAnimationRunning) {
            mLpChanged.privateFlags |= PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
        } else {
            mLpChanged.privateFlags &= ~PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
        }
    }
}
