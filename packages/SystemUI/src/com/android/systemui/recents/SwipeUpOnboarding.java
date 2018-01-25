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

package com.android.systemui.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.misc.SysUiTaskStackChangeListener;
import com.android.systemui.shared.system.ActivityManagerWrapper;

/**
 * Shows onboarding for the new recents interaction in P (codenamed quickstep).
 */
@TargetApi(Build.VERSION_CODES.P)
public class SwipeUpOnboarding {

    private static final String TAG = "SwipeUpOnboarding";
    private static final boolean RESET_PREFS_FOR_DEBUG = false;
    private static final long SHOW_DELAY_MS = 500;
    private static final long SHOW_HIDE_DURATION_MS = 300;
    // Don't show the onboarding until the user has launched this number of apps.
    private static final int SHOW_ON_APP_LAUNCH = 2;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final View mLayout;
    private final TextView mTextView;
    private final ImageView mDismissView;
    private final ColorDrawable mBackgroundDrawable;
    private final int mDarkBackgroundColor;
    private final int mLightBackgroundColor;
    private final int mDarkContentColor;
    private final int mLightContentColor;
    private final RippleDrawable mDarkRipple;
    private final RippleDrawable mLightRipple;

    private boolean mTaskListenerRegistered;
    private ComponentName mLauncherComponent;
    private boolean mLayoutAttachedToWindow;
    private boolean mBackgroundIsLight;

    private final SysUiTaskStackChangeListener mTaskListener = new SysUiTaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            ActivityManager.RunningTaskInfo info = ActivityManagerWrapper.getInstance()
                    .getRunningTask(ACTIVITY_TYPE_UNDEFINED /* ignoreActivityType */);
            int activityType = info.configuration.windowConfiguration.getActivityType();
            int numAppsLaunched = Prefs.getInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, 0);
            if (activityType == ACTIVITY_TYPE_STANDARD) {
                numAppsLaunched++;
                if (numAppsLaunched >= SHOW_ON_APP_LAUNCH) {
                    show();
                } else {
                    Prefs.putInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, numAppsLaunched);
                }
            } else {
                String runningPackage = info.topActivity.getPackageName();
                // TODO: use callback from the overview proxy service to handle this case
                if (runningPackage.equals(mLauncherComponent.getPackageName())
                        && activityType == ACTIVITY_TYPE_RECENTS) {
                    Prefs.putBoolean(mContext, Prefs.Key.HAS_SWIPED_UP_FOR_RECENTS, true);
                    onDisconnectedFromLauncher();
                } else {
                    hide(false);
                }
            }
        }
    };

    private final View.OnAttachStateChangeListener mOnAttachStateChangeListener
            = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
            if (view == mLayout) {
                mLayoutAttachedToWindow = true;
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (view == mLayout) {
                mLayoutAttachedToWindow = false;
            }
        }
    };

    public SwipeUpOnboarding(Context context) {
        mContext = context;
        final Resources res = context.getResources();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mLayout = LayoutInflater.from(mContext).inflate(R.layout.recents_swipe_up_onboarding, null);
        mTextView = (TextView) mLayout.findViewById(R.id.onboarding_text);
        mDismissView = (ImageView) mLayout.findViewById(R.id.dismiss);
        mDarkBackgroundColor = res.getColor(android.R.color.background_dark);
        mLightBackgroundColor = res.getColor(android.R.color.background_light);
        mDarkContentColor = res.getColor(R.color.primary_text_default_material_light);
        mLightContentColor = res.getColor(R.color.primary_text_default_material_dark);
        mDarkRipple = new RippleDrawable(res.getColorStateList(R.color.ripple_material_light),
                null, null);
        mLightRipple = new RippleDrawable(res.getColorStateList(R.color.ripple_material_dark),
                null, null);
        mBackgroundDrawable = new ColorDrawable(mDarkBackgroundColor);

        mLayout.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        mLayout.setBackground(mBackgroundDrawable);
        mDismissView.setOnClickListener(v -> hide(true));

        if (RESET_PREFS_FOR_DEBUG) {
            Prefs.putBoolean(mContext, Prefs.Key.HAS_SWIPED_UP_FOR_RECENTS, false);
            Prefs.putInt(mContext, Prefs.Key.NUM_APPS_LAUNCHED, 0);
        }
    }

    public void onConnectedToLauncher(ComponentName launcherComponent) {
        // TODO: re-enable this once we have the proper callback for when a swipe up was performed.
        final boolean disableOnboarding = true;
        if (disableOnboarding) {
            return;
        }
        mLauncherComponent = launcherComponent;
        boolean alreadyLearnedSwipeUpForRecents = Prefs.getBoolean(mContext,
                Prefs.Key.HAS_SWIPED_UP_FOR_RECENTS, false);
        if (!mTaskListenerRegistered && !alreadyLearnedSwipeUpForRecents) {
            ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskListener);
            mTaskListenerRegistered = true;
        }
    }

    public void onDisconnectedFromLauncher() {
        if (mTaskListenerRegistered) {
            ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskListener);
            mTaskListenerRegistered = false;
        }
        hide(false);
    }

    public void onConfigurationChanged(Configuration newConfiguration) {
        if (newConfiguration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            hide(false);
        }
    }

    public void show() {
        // Only show in portrait.
        int orientation = mContext.getResources().getConfiguration().orientation;
        if (!mLayoutAttachedToWindow && orientation == Configuration.ORIENTATION_PORTRAIT) {
            mLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            mWindowManager.addView(mLayout, getWindowLayoutParams());
            int layoutHeight = mLayout.getHeight();
            if (layoutHeight == 0) {
                mLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                layoutHeight = mLayout.getMeasuredHeight();
            }
            mLayout.setTranslationY(layoutHeight);
            mLayout.setAlpha(0);
            mLayout.animate()
                    .translationY(0)
                    .alpha(1f)
                    .withLayer()
                    .setStartDelay(SHOW_DELAY_MS)
                    .setDuration(SHOW_HIDE_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    public void hide(boolean animate) {
        if (mLayoutAttachedToWindow) {
            if (animate) {
                mLayout.animate()
                        .translationY(mLayout.getHeight())
                        .alpha(0f)
                        .withLayer()
                        .setDuration(SHOW_HIDE_DURATION_MS)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> mWindowManager.removeView(mLayout))
                        .start();
            } else {
                mWindowManager.removeView(mLayout);
            }
        }
    }

    public void setContentDarkIntensity(float contentDarkIntensity) {
        boolean backgroundIsLight = contentDarkIntensity > 0.5f;
        if (backgroundIsLight != mBackgroundIsLight) {
            mBackgroundIsLight = backgroundIsLight;
            mBackgroundDrawable.setColor(mBackgroundIsLight
                    ? mLightBackgroundColor : mDarkBackgroundColor);
            int contentColor = mBackgroundIsLight ? mDarkContentColor : mLightContentColor;
            mTextView.setTextColor(contentColor);
            mTextView.getCompoundDrawables()[3].setColorFilter(contentColor,
                    PorterDuff.Mode.SRC_IN);
            mDismissView.setColorFilter(contentColor);
            mDismissView.setBackground(mBackgroundIsLight ? mDarkRipple : mLightRipple);
        }
    }

    private WindowManager.LayoutParams getWindowLayoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("SwipeUpOnboarding");
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }
}
