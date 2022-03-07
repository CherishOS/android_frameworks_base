/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.keyguard;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import androidx.annotation.StyleRes;

import com.android.systemui.animation.Interpolators;
import com.android.systemui.util.Utils;

/**
 * Provides background color and radius animations for key pad buttons.
 */
class NumPadAnimator {
    private ValueAnimator mExpandAnimator;
    private AnimatorSet mExpandAnimatorSet;
    private ValueAnimator mContractAnimator;
    private AnimatorSet mContractAnimatorSet;
    private GradientDrawable mBackground;
    private int mNormalColor;
    private int mHighlightColor;
    private int mStyle;
    private static final int EXPAND_ANIMATION_MS = 100;
    private static final int EXPAND_COLOR_ANIMATION_MS = 50;
    private static final int CONTRACT_ANIMATION_DELAY_MS = 33;
    private static final int CONTRACT_ANIMATION_MS = 417;

    NumPadAnimator(Context context, final Drawable drawable, @StyleRes int style) {
        this(context, drawable, style, null);
    }

    NumPadAnimator(Context context, final Drawable drawable, @StyleRes int style,
            @Nullable TextView digitTextView) {
        mStyle = style;
        mBackground = (GradientDrawable) drawable;

        reloadColors(context);
        int textColorPrimary = com.android.settingslib.Utils
                .getColorAttrDefaultColor(context, android.R.attr.textColorPrimary);
        int textColorPrimaryInverse = com.android.settingslib.Utils
                .getColorAttrDefaultColor(context, android.R.attr.textColorPrimaryInverse);

        // Actual values will be updated later, usually during an onLayout() call
        mExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
        mExpandAnimator.setDuration(EXPAND_ANIMATION_MS);
        mExpandAnimator.setInterpolator(Interpolators.LINEAR);
        mExpandAnimator.addUpdateListener(
                anim -> mBackground.setCornerRadius((float) anim.getAnimatedValue()));

        ValueAnimator expandBackgroundColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                mNormalColor, mHighlightColor);
        expandBackgroundColorAnimator.setDuration(EXPAND_COLOR_ANIMATION_MS);
        expandBackgroundColorAnimator.setInterpolator(Interpolators.LINEAR);
        expandBackgroundColorAnimator.addUpdateListener(
                animator -> mBackground.setColor((int) animator.getAnimatedValue()));

        ValueAnimator expandTextColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(),
                textColorPrimary, textColorPrimaryInverse);
        expandTextColorAnimator.setInterpolator(Interpolators.LINEAR);
        expandTextColorAnimator.setDuration(EXPAND_COLOR_ANIMATION_MS);
        expandTextColorAnimator.addUpdateListener(valueAnimator -> {
            if (digitTextView != null) {
                digitTextView.setTextColor((int) valueAnimator.getAnimatedValue());
            }
        });

        mExpandAnimatorSet = new AnimatorSet();
        mExpandAnimatorSet.playTogether(mExpandAnimator,
                expandBackgroundColorAnimator, expandTextColorAnimator);

        mContractAnimator = ValueAnimator.ofFloat(1f, 0f);
        mContractAnimator.setStartDelay(CONTRACT_ANIMATION_DELAY_MS);
        mContractAnimator.setDuration(CONTRACT_ANIMATION_MS);
        mContractAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mContractAnimator.addUpdateListener(
                anim -> mBackground.setCornerRadius((float) anim.getAnimatedValue()));
        ValueAnimator contractBackgroundColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                mHighlightColor, mNormalColor);
        contractBackgroundColorAnimator.setInterpolator(Interpolators.LINEAR);
        contractBackgroundColorAnimator.setStartDelay(CONTRACT_ANIMATION_DELAY_MS);
        contractBackgroundColorAnimator.setDuration(CONTRACT_ANIMATION_MS);
        contractBackgroundColorAnimator.addUpdateListener(
                animator -> mBackground.setColor((int) animator.getAnimatedValue()));

        ValueAnimator contractTextColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(), textColorPrimaryInverse,
                textColorPrimary);
        contractTextColorAnimator.setInterpolator(Interpolators.LINEAR);
        contractTextColorAnimator.setStartDelay(CONTRACT_ANIMATION_DELAY_MS);
        contractTextColorAnimator.setDuration(CONTRACT_ANIMATION_MS);
        contractTextColorAnimator.addUpdateListener(valueAnimator -> {
            if (digitTextView != null) {
                digitTextView.setTextColor((int) valueAnimator.getAnimatedValue());
            }
        });

        mContractAnimatorSet = new AnimatorSet();
        mContractAnimatorSet.playTogether(mContractAnimator,
                contractBackgroundColorAnimator, contractTextColorAnimator);
    }

    public void expand() {
        mExpandAnimatorSet.cancel();
        mContractAnimatorSet.cancel();
        mExpandAnimatorSet.start();
    }

    public void contract() {
        mExpandAnimatorSet.cancel();
        mContractAnimatorSet.cancel();
        mContractAnimatorSet.start();
    }

    void onLayout(int height) {
        float startRadius = height / 2f;
        float endRadius = height / 4f;
        mBackground.setCornerRadius(startRadius);
        mExpandAnimator.setFloatValues(startRadius, endRadius);
        mContractAnimator.setFloatValues(endRadius, startRadius);
    }

    /**
     * Reload colors from resources.
     **/
    void reloadColors(Context context) {
        int[] customAttrs = {android.R.attr.colorControlNormal,
                android.R.attr.colorControlHighlight};

        ContextThemeWrapper ctw = new ContextThemeWrapper(context, mStyle);
        TypedArray a = ctw.obtainStyledAttributes(customAttrs);
        mNormalColor = Utils.getPrivateAttrColorIfUnset(ctw, a, 0, 0,
                com.android.internal.R.attr.colorSurface);
        mHighlightColor = a.getColor(1, 0);
        a.recycle();

        mBackground.setColor(mNormalColor);
    }
}

