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

package com.android.systemui.charging;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Animatable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.animation.PathInterpolator;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.airbnb.lottie.LottieAnimationView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.text.NumberFormat;

/**
 * @hide
 */
public class WirelessChargingLayout extends FrameLayout {
    public final static int UNKNOWN_BATTERY_LEVEL = -1;

    private int mChargingAnimation;

    public WirelessChargingLayout(Context context) {
        super(context);
        init(context, null, false);
    }

    public WirelessChargingLayout(Context context, int transmittingBatteryLevel, int batteryLevel,
            boolean isDozing) {
        super(context);
        init(context, null, transmittingBatteryLevel, batteryLevel, isDozing);
    }

    public WirelessChargingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, false);
    }

    private void init(Context c, AttributeSet attrs, boolean isDozing) {
        init(c, attrs, -1, -1, false);
    }

    private void init(Context context, AttributeSet attrs, int transmittingBatteryLevel,
            int batteryLevel, boolean isDozing) {
        final boolean showTransmittingBatteryLevel =
                (transmittingBatteryLevel != UNKNOWN_BATTERY_LEVEL);

        mChargingAnimation = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.CHARGING_ANIMATION_STYLE, 1, UserHandle.USER_CURRENT);

        // set style based on background
        int style;
        if (mChargingAnimation == 0) {
            style = R.style.ChargingAnim_WallpaperBackground;
            if (isDozing) {
                style = R.style.ChargingAnim_DarkBackground;
            }
        } else {
            style = R.style.ChargingLottieAnim_WallpaperBackground;
            if (isDozing) {
                style = R.style.ChargingLottieAnim_DarkBackground;
            }
        }

        inflate(new ContextThemeWrapper(context, style), R.layout.wireless_charging_layout, this);

        // where the circle animation occurs:
        final ImageView chargingView = findViewById(R.id.wireless_charging_view);
        final Animatable chargingAnimation = (Animatable) chargingView.getDrawable();
        final LottieAnimationView chargingLottie = findViewById(R.id.wireless_charging_lottie);

        switch (mChargingAnimation) {
            default:
            case 1:
                chargingLottie.setFileName("sdb_charging_animation.json");
		chargingLottie.setSpeed(1.3f);
                break;
            case 2:
                chargingLottie.setFileName("meme_ui_animation.json");
		chargingLottie.setSpeed(0.5f);
                break;
            case 3:
                chargingLottie.setFileName("explosion_charging_animation.json");
                chargingLottie.setSpeed(1f);
                break;
            case 4:
                chargingLottie.setFileName("rainbow_charging_animation.json");
                chargingLottie.setSpeed(1f);
                break;
            case 5:
                chargingLottie.setFileName("fire_charging_animation.json");
                chargingLottie.setSpeed(1.3f);
                break;
        }
        if (mChargingAnimation > 0) {
            chargingView.setVisibility(View.GONE);
            chargingLottie.setVisibility(View.VISIBLE);
        } else {
            chargingLottie.setVisibility(View.GONE);
            chargingView.setVisibility(View.VISIBLE);
        }

        // amount of battery:
        final TextView percentage = findViewById(R.id.wireless_charging_percentage);

        if (batteryLevel != UNKNOWN_BATTERY_LEVEL) {
            percentage.setText(NumberFormat.getPercentInstance().format(batteryLevel / 100f));
            percentage.setAlpha(0);
        }

        final long chargingAnimationFadeStartOffset = context.getResources().getInteger(
                R.integer.wireless_charging_fade_offset);
        final long chargingAnimationFadeDuration = context.getResources().getInteger(
                R.integer.wireless_charging_fade_duration);
        final float batteryLevelTextSizeStart = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_start);
        final float batteryLevelTextSizeEnd = context.getResources().getFloat(
                R.dimen.wireless_charging_anim_battery_level_text_size_end) * (
                showTransmittingBatteryLevel ? 0.75f : 1.0f);

        // Animation Scale: battery percentage text scales from 0% to 100%
        ValueAnimator textSizeAnimator = ObjectAnimator.ofFloat(percentage, "textSize",
                batteryLevelTextSizeStart, batteryLevelTextSizeEnd);
        textSizeAnimator.setInterpolator(new PathInterpolator(0, 0, 0, 1));
        textSizeAnimator.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_scale_animation_duration));

        // Animation Opacity: battery percentage text transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimator = ObjectAnimator.ofFloat(percentage, "alpha", 0, 1);
        textOpacityAnimator.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimator.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimator.setStartDelay(context.getResources().getInteger(
                R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: battery percentage text fades from 1 to 0 opacity
        ValueAnimator textFadeAnimator = ObjectAnimator.ofFloat(percentage, "alpha", 1, 0);
        textFadeAnimator.setDuration(chargingAnimationFadeDuration);
        textFadeAnimator.setInterpolator(Interpolators.LINEAR);
        textFadeAnimator.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(textSizeAnimator, textOpacityAnimator, textFadeAnimator);

        if (!showTransmittingBatteryLevel) {
            if (mChargingAnimation > 0) {
                chargingLottie.playAnimation();
            } else {
                chargingAnimation.start();
            }
            animatorSet.start();
            return;
        }

        // amount of transmitting battery:
        final TextView transmittingPercentage = findViewById(
                R.id.reverse_wireless_charging_percentage);
        transmittingPercentage.setVisibility(VISIBLE);
        transmittingPercentage.setText(
                NumberFormat.getPercentInstance().format(transmittingBatteryLevel / 100f));
        transmittingPercentage.setAlpha(0);

        // Animation Scale: transmitting battery percentage text scales from 0% to 100%
        ValueAnimator textSizeAnimatorTransmitting = ObjectAnimator.ofFloat(transmittingPercentage,
                "textSize", batteryLevelTextSizeStart, batteryLevelTextSizeEnd);
        textSizeAnimatorTransmitting.setInterpolator(new PathInterpolator(0, 0, 0, 1));
        textSizeAnimatorTransmitting.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_scale_animation_duration));

        // Animation Opacity: transmitting battery percentage text transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimatorTransmitting = ObjectAnimator.ofFloat(
                transmittingPercentage, "alpha", 0, 1);
        textOpacityAnimatorTransmitting.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimatorTransmitting.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimatorTransmitting.setStartDelay(
                context.getResources().getInteger(R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: transmitting battery percentage text fades from 1 to 0 opacity
        ValueAnimator textFadeAnimatorTransmitting = ObjectAnimator.ofFloat(transmittingPercentage,
                "alpha", 1, 0);
        textFadeAnimatorTransmitting.setDuration(chargingAnimationFadeDuration);
        textFadeAnimatorTransmitting.setInterpolator(Interpolators.LINEAR);
        textFadeAnimatorTransmitting.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSetTransmitting = new AnimatorSet();
        animatorSetTransmitting.playTogether(textSizeAnimatorTransmitting,
                textOpacityAnimatorTransmitting, textFadeAnimatorTransmitting);

        // transmitting battery icon
        final ImageView chargingViewIcon = findViewById(R.id.reverse_wireless_charging_icon);
        chargingViewIcon.setVisibility(VISIBLE);
        final int padding = Math.round(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, batteryLevelTextSizeEnd,
                        getResources().getDisplayMetrics()));
        chargingViewIcon.setPadding(padding, 0, padding, 0);

        // Animation Opacity: transmitting battery icon transitions from 0 to 1 opacity
        ValueAnimator textOpacityAnimatorIcon = ObjectAnimator.ofFloat(chargingViewIcon, "alpha", 0,
                1);
        textOpacityAnimatorIcon.setInterpolator(Interpolators.LINEAR);
        textOpacityAnimatorIcon.setDuration(context.getResources().getInteger(
                R.integer.wireless_charging_battery_level_text_opacity_duration));
        textOpacityAnimatorIcon.setStartDelay(
                context.getResources().getInteger(R.integer.wireless_charging_anim_opacity_offset));

        // Animation Opacity: transmitting battery icon fades from 1 to 0 opacity
        ValueAnimator textFadeAnimatorIcon = ObjectAnimator.ofFloat(chargingViewIcon, "alpha", 1,
                0);
        textFadeAnimatorIcon.setDuration(chargingAnimationFadeDuration);
        textFadeAnimatorIcon.setInterpolator(Interpolators.LINEAR);
        textFadeAnimatorIcon.setStartDelay(chargingAnimationFadeStartOffset);

        // play all animations together
        AnimatorSet animatorSetIcon = new AnimatorSet();
        animatorSetIcon.playTogether(textOpacityAnimatorIcon, textFadeAnimatorIcon);

        if (mChargingAnimation > 0) {
            chargingLottie.playAnimation();
        } else {
            chargingAnimation.start();
        }
        animatorSet.start();
        animatorSetTransmitting.start();
        animatorSetIcon.start();
    }
}
