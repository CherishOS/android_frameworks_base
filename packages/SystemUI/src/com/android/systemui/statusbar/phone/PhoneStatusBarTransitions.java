/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.content.res.Resources;
import android.view.View;

import android.content.ContentResolver;

import com.android.settingslib.Utils;

import com.android.systemui.R;

public final class PhoneStatusBarTransitions extends BarTransitions {
    private static final float ICON_ALPHA_WHEN_NOT_OPAQUE = 1;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK = 0.5f;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK = 0;

    private final float mIconAlphaWhenOpaque;

    private View mStartSide, mStatusIcons, mBattery;
    private Animator mCurrentAnimation;

    private static final class GradientObserver extends ContentObserver {
        private static final Uri DYNAMIC_SYSTEM_BARS_GRADIENT_URI = Settings.System.getUriFor(
                "DYNAMIC_SYSTEM_BARS_GRADIENT_STATE");

        private final PhoneStatusBarBackgroundDrawable mDrawable;

        GradientObserver(PhoneStatusBarBackgroundDrawable drawable,
                         Handler handler) {
            super(handler);
            mDrawable = drawable;
        }

        @Override
        public void onChange(boolean selfChange) {
            int alpha = Settings.System.getInt(
                    mDrawable.mContext.getContentResolver(),
                    "DYNAMIC_SYSTEM_BARS_GRADIENT_STATE", 0) == 1 ? 0xff : 0;

            mDrawable.setOverrideGradientAlpha(alpha);
        }
    }

    protected static class PhoneStatusBarBackgroundDrawable extends BarTransitions.BarBackgroundDrawable {
        private final Context mContext;

        private int mOverrideColor = 0;
        private int mOverrideGradientAlpha = 0;

        public PhoneStatusBarBackgroundDrawable(Context context) {
            super(context, R.drawable.status_background, R.color.system_bar_background_transparent,
                    com.android.internal.R.color.system_bar_background_semi_transparent,
                    R.color.system_bar_background_transparent,
                    Utils.getColorAttrDefaultColor(context, android.R.attr.colorError));
            mContext = context;

            final GradientObserver obs = new GradientObserver(this, new Handler());
            (mContext.getContentResolver()).registerContentObserver(
                    GradientObserver.DYNAMIC_SYSTEM_BARS_GRADIENT_URI,
                    false, obs, UserHandle.USER_ALL);

            mOverrideGradientAlpha = Settings.System.getInt(mContext.getContentResolver(),
                    "DYNAMIC_SYSTEM_BARS_GRADIENT_STATE", 0) == 1 ? 0xff : 0;

            BarBackgroundUpdater.addListener(new BarBackgroundUpdater.UpdateListener(this) {

                @Override
                public void onUpdateStatusBarColor(final int previousColor,
                                                   final int color) {
                    mOverrideColor = color;

                    generateAnimator();

                }

            });
            BarBackgroundUpdater.init(context);
        }

        @Override
        protected int getColorOpaque() {
            return mOverrideColor == 0 ? super.getColorOpaque() : mOverrideColor;
        }

        @Override
        protected int getColorSemiTransparent() {
            return mOverrideColor == 0 ? super.getColorSemiTransparent() : (mOverrideColor & 0x00ffffff | 0x7f000000);
        }

        @Override
        protected int getGradientAlphaOpaque() {
            return mOverrideGradientAlpha;
        }

        @Override
        protected int getGradientAlphaSemiTransparent() {
            return mOverrideGradientAlpha & 127;
        }

        public void setOverrideGradientAlpha(int alpha) {
            mOverrideGradientAlpha = alpha;
            generateAnimator();
        }
    }

    /**
     * @param backgroundView view to apply the background drawable
     */
    public PhoneStatusBarTransitions(PhoneStatusBarView statusBarView, View backgroundView) {
        /*super(backgroundView, R.drawable.status_background);
         */
        super(backgroundView, new PhoneStatusBarBackgroundDrawable(backgroundView.getContext()));

        final Resources res = statusBarView.getContext().getResources();
        mIconAlphaWhenOpaque = res.getFraction(R.dimen.status_bar_icon_drawing_alpha, 1, 1);
        mStartSide = statusBarView.findViewById(R.id.status_bar_start_side_except_heads_up);
        mStatusIcons = statusBarView.findViewById(R.id.statusIcons);
        mBattery = statusBarView.findViewById(R.id.battery);
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyMode(getMode(), false /*animate*/);
    }

    public ObjectAnimator animateTransitionTo(View v, float toAlpha) {
        return ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), toAlpha);
    }

    private float getNonBatteryClockAlphaFor(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK
                : !isOpaque(mode) ? ICON_ALPHA_WHEN_NOT_OPAQUE
                : mIconAlphaWhenOpaque;
    }

    private float getBatteryClockAlpha(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK
                : getNonBatteryClockAlphaFor(mode);
    }

    private boolean isOpaque(int mode) {
        return !(mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT
                || mode == MODE_TRANSPARENT || mode == MODE_LIGHTS_OUT_TRANSPARENT);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate);
    }

    private void applyMode(int mode, boolean animate) {
        if (mStartSide == null) return; // pre-init
        float newAlpha = getNonBatteryClockAlphaFor(mode);
        float newAlphaBC = getBatteryClockAlpha(mode);
        if (mCurrentAnimation != null) {
            mCurrentAnimation.cancel();
        }
        if (animate) {
            AnimatorSet anims = new AnimatorSet();
            anims.playTogether(
                    animateTransitionTo(mStartSide, newAlpha),
                    animateTransitionTo(mStatusIcons, newAlpha),
                    animateTransitionTo(mBattery, newAlphaBC)
            );
            if (isLightsOut(mode)) {
                anims.setDuration(LIGHTS_OUT_DURATION);
            }
            anims.start();
            mCurrentAnimation = anims;
        } else {
            mStartSide.setAlpha(newAlpha);
            mStatusIcons.setAlpha(newAlpha);
            mBattery.setAlpha(newAlphaBC);
        }
    }
}
