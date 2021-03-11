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

package com.android.systemui.biometrics;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

/**
 * Manages the layout for under-display fingerprint sensors (UDFPS). Ensures that UI elements
 * do not overlap with
 */
public class AuthBiometricUdfpsView extends AuthBiometricFingerprintView {

    private static final String TAG = "AuthBiometricUdfpsView";

    @Nullable private FingerprintSensorPropertiesInternal mSensorProps;

    public AuthBiometricUdfpsView(Context context) {
        this(context, null /* attrs */);
    }

    public AuthBiometricUdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSensorProps(@NonNull FingerprintSensorPropertiesInternal prop) {
        mSensorProps = prop;
    }

    @Override
    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        final int displayRotation = getDisplay().getRotation();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                return onMeasureInternalPortrait(width, height);
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                return onMeasureInternalLandscape(width, height);
            default:
                Log.e(TAG, "Unsupported display rotation: " + displayRotation);
                return super.onMeasureInternal(width, height);
        }
    }

    @NonNull
    private AuthDialog.LayoutParams onMeasureInternalPortrait(int width, int height) {
        // Get the height of the everything below the icon. Currently, that's the indicator and
        // button bar.
        final int textIndicatorHeight = getViewHeightPx(R.id.indicator);
        final int buttonBarHeight = getViewHeightPx(R.id.button_bar);

        // Figure out where the bottom of the sensor anim should be.
        // Navbar + dialogMargin + buttonBar + textIndicator + spacerHeight = sensorDistFromBottom
        final int dialogMargin = getDialogMarginPx();
        final WindowManager windowManager = getContext().getSystemService(WindowManager.class);
        final int displayHeight = getWindowBounds(windowManager).height();
        final Insets navbarInsets = getNavbarInsets(windowManager);
        final int bottomSpacerHeight = calculateBottomSpacerHeightForPortrait(
                mSensorProps, displayHeight, textIndicatorHeight, buttonBarHeight,
                dialogMargin, navbarInsets.bottom);

        // Go through each of the children and do the custom measurement.
        int totalHeight = 0;
        final int numChildren = getChildCount();
        final int sensorDiameter = mSensorProps.sensorRadius * 2;
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);
            if (child.getId() == R.id.biometric_icon_frame) {
                final FrameLayout iconFrame = (FrameLayout) child;
                final View icon = iconFrame.getChildAt(0);

                // Ensure that the icon is never larger than the sensor.
                icon.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST));

                // Create a frame that's exactly the height of the sensor circle.
                iconFrame.measure(
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_above_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().height, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.button_bar) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_below_icon) {
                // Set the spacer height so the fingerprint icon is on the physical sensor area
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(bottomSpacerHeight, MeasureSpec.EXACTLY));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                totalHeight += child.getMeasuredHeight();
            }
        }

        return new AuthDialog.LayoutParams(width, totalHeight);
    }

    @NonNull
    private AuthDialog.LayoutParams onMeasureInternalLandscape(int width, int height) {
        // Find the spacer height needed to vertically align the icon with the sensor.
        final int titleHeight = getViewHeightPx(R.id.title);
        final int subtitleHeight = getViewHeightPx(R.id.subtitle);
        final int descriptionHeight = getViewHeightPx(R.id.description);
        final int topSpacerHeight = getViewHeightPx(R.id.space_above_icon);
        final int textIndicatorHeight = getViewHeightPx(R.id.indicator);
        final int buttonBarHeight = getViewHeightPx(R.id.button_bar);
        final WindowManager windowManager = getContext().getSystemService(WindowManager.class);
        final Insets navbarInsets = getNavbarInsets(windowManager);
        final int bottomSpacerHeight = calculateBottomSpacerHeightForLandscape(titleHeight,
                subtitleHeight, descriptionHeight, topSpacerHeight, textIndicatorHeight,
                buttonBarHeight, navbarInsets.bottom);

        // Find the spacer width needed to horizontally align the icon with the sensor.
        final int displayWidth = getWindowBounds(windowManager).width();
        final int dialogMargin = getDialogMarginPx();
        final int horizontalInset = navbarInsets.left + navbarInsets.right;
        final int horizontalSpacerWidth = calculateHorizontalSpacerWidthForLandscape(
                mSensorProps, displayWidth, dialogMargin, horizontalInset);

        final int sensorDiameter = mSensorProps.sensorRadius * 2;
        final int remeasuredWidth = sensorDiameter + 2 * horizontalSpacerWidth;

        int remeasuredHeight = 0;
        final int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);
            if (child.getId() == R.id.biometric_icon_frame) {
                final FrameLayout iconFrame = (FrameLayout) child;
                final View icon = iconFrame.getChildAt(0);

                // Ensure that the icon is never larger than the sensor.
                icon.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST));

                // Create a frame that's exactly the height of the sensor circle.
                iconFrame.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_above_icon || child.getId() == R.id.button_bar) {
                // Adjust the width of the top spacer and button bar while preserving their heights.
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().height, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_below_icon) {
                // Adjust the bottom spacer height to align the fingerprint icon with the sensor.
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(bottomSpacerHeight, MeasureSpec.EXACTLY));
            } else {
                // Use the remeasured width for all other child views.
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                remeasuredHeight += child.getMeasuredHeight();
            }
        }

        return new AuthDialog.LayoutParams(remeasuredWidth, remeasuredHeight);
    }

    private int getViewHeightPx(@IdRes int viewId) {
        final View view = findViewById(viewId);
        return view != null ? view.getMeasuredHeight() : 0;
    }

    private int getDialogMarginPx() {
        return getResources().getDimensionPixelSize(R.dimen.biometric_dialog_border_padding);
    }

    @NonNull
    private static Insets getNavbarInsets(@Nullable WindowManager windowManager) {
        return windowManager != null && windowManager.getCurrentWindowMetrics() != null
                ? windowManager.getCurrentWindowMetrics().getWindowInsets()
                        .getInsets(WindowInsets.Type.navigationBars())
                : Insets.NONE;
    }

    @NonNull
    private static Rect getWindowBounds(@Nullable WindowManager windowManager) {
        return windowManager != null && windowManager.getCurrentWindowMetrics() != null
                ? windowManager.getCurrentWindowMetrics().getBounds()
                : new Rect();
    }

    /**
     * For devices in portrait orientation where the sensor is too high up, calculates the amount of
     * padding necessary to center the biometric icon within the sensor's physical location.
     */
    @VisibleForTesting
    static int calculateBottomSpacerHeightForPortrait(
            @NonNull FingerprintSensorPropertiesInternal sensorProperties, int displayHeightPx,
            int textIndicatorHeightPx, int buttonBarHeightPx, int dialogMarginPx,
            int navbarBottomInsetPx) {

        final int sensorDistanceFromBottom = displayHeightPx
                - sensorProperties.sensorLocationY
                - sensorProperties.sensorRadius;

        final int spacerHeight = sensorDistanceFromBottom
                - textIndicatorHeightPx
                - buttonBarHeightPx
                - dialogMarginPx
                - navbarBottomInsetPx;

        Log.d(TAG, "Display height: " + displayHeightPx
                + ", Distance from bottom: " + sensorDistanceFromBottom
                + ", Bottom margin: " + dialogMarginPx
                + ", Navbar bottom inset: " + navbarBottomInsetPx
                + ", Bottom spacer height (portrait): " + spacerHeight);

        return spacerHeight;
    }

    /**
     * For devices in landscape orientation where the sensor is too high up, calculates the amount
     * of padding necessary to center the biometric icon within the sensor's physical location.
     */
    @VisibleForTesting
    static int calculateBottomSpacerHeightForLandscape(int titleHeightPx, int subtitleHeightPx,
            int descriptionHeightPx, int topSpacerHeightPx, int textIndicatorHeightPx,
            int buttonBarHeightPx, int navbarBottomInsetPx) {

        final int dialogHeightAboveIcon = titleHeightPx
                + subtitleHeightPx
                + descriptionHeightPx
                + topSpacerHeightPx;

        final int dialogHeightBelowIcon = textIndicatorHeightPx + buttonBarHeightPx;

        final int bottomSpacerHeight = dialogHeightAboveIcon
                - dialogHeightBelowIcon
                - navbarBottomInsetPx;

        Log.d(TAG, "Title height: " + titleHeightPx
                + ", Subtitle height: " + subtitleHeightPx
                + ", Description height: " + descriptionHeightPx
                + ", Top spacer height: " + topSpacerHeightPx
                + ", Text indicator height: " + textIndicatorHeightPx
                + ", Button bar height: " + buttonBarHeightPx
                + ", Navbar bottom inset: " + navbarBottomInsetPx
                + ", Bottom spacer height (landscape): " + bottomSpacerHeight);

        return bottomSpacerHeight;
    }

    /**
     * For devices in landscape orientation where the sensor is too left/right, calculates the
     * amount of padding necessary to center the biometric icon within the sensor's physical
     * location.
     */
    @VisibleForTesting
    static int calculateHorizontalSpacerWidthForLandscape(
            @NonNull FingerprintSensorPropertiesInternal sensorProperties, int displayWidthPx,
            int dialogMarginPx, int navbarHorizontalInsetPx) {

        final int sensorDistanceFromEdge = displayWidthPx
                - sensorProperties.sensorLocationY
                - sensorProperties.sensorRadius;

        final int horizontalPadding = sensorDistanceFromEdge
                - dialogMarginPx
                - navbarHorizontalInsetPx;

        Log.d(TAG, "Display width: " + displayWidthPx
                + ", Distance from edge: " + sensorDistanceFromEdge
                + ", Dialog margin: " + dialogMarginPx
                + ", Navbar horizontal inset: " + navbarHorizontalInsetPx
                + ", Horizontal spacer width (landscape): " + horizontalPadding);

        return horizontalPadding;
    }
}
