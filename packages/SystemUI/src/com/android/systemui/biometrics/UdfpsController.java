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

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.MathUtils;
import android.util.Spline;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.BrightnessSynchronizer;
import com.android.systemui.R;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 */
class UdfpsController {
    private static final String TAG = "UdfpsController";
    // Gamma approximation for the sRGB color space.
    private static final float DISPLAY_GAMMA = 2.2f;

    private final FingerprintManager mFingerprintManager;
    private final WindowManager mWindowManager;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final WindowManager.LayoutParams mLayoutParams;
    private final UdfpsView mView;
    // Debugfs path to control the high-brightness mode.
    private final String mHbmPath;
    private final String mHbmEnableCommand;
    private final String mHbmDisableCommand;
    // Brightness in nits in the high-brightness mode.
    private final float mHbmNits;
    // A spline mapping from the device's backlight value, normalized to the range [0, 1.0], to a
    // brightness in nits.
    private final Spline mBacklightToNitsSpline;
    // A spline mapping from a value in nits to a backlight value of a hypothetical panel whose
    // maximum backlight value corresponds to our panel's high-brightness mode.
    // The output is normalized to the range [0, 1.0].
    private Spline mNitsToHbmBacklightSpline;
    // Default non-HBM backlight value normalized to the range [0, 1.0]. Used as a fallback when the
    // actual brightness value cannot be retrieved.
    private final float mDefaultBrightness;
    private boolean mIsOverlayShowing;

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay() {
            UdfpsController.this.showUdfpsOverlay();
        }

        @Override
        public void hideUdfpsOverlay() {
            UdfpsController.this.hideUdfpsOverlay();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnTouchListener mOnTouchListener = (v, event) -> {
        UdfpsView view = (UdfpsView) v;
        final boolean isFingerDown = view.isScrimShowing();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                final boolean isValidTouch = view.isValidTouch(event.getX(), event.getY(),
                        event.getPressure());
                if (!isFingerDown && isValidTouch) {
                    onFingerDown((int) event.getX(), (int) event.getY(), event.getTouchMinor(),
                            event.getTouchMajor());
                } else if (isFingerDown && !isValidTouch) {
                    onFingerUp();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isFingerDown) {
                    onFingerUp();
                }
                return true;

            default:
                return false;
        }
    };

    UdfpsController(Context context) {
        mFingerprintManager = context.getSystemService(FingerprintManager.class);
        mWindowManager = context.getSystemService(WindowManager.class);
        mContentResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mLayoutParams = createLayoutParams(context);

        mView = (UdfpsView) LayoutInflater.from(context).inflate(R.layout.udfps_view, null, false);

        mHbmPath = context.getResources().getString(R.string.udfps_hbm_sysfs_path);
        mHbmEnableCommand = context.getResources().getString(R.string.udfps_hbm_enable_command);
        mHbmDisableCommand = context.getResources().getString(R.string.udfps_hbm_disable_command);

        // This range only consists of the minimum and maximum values, which only cover
        // non-high-brightness mode.
        float[] nitsRange = toFloatArray(context.getResources().obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits));

        // The last value of this range corresponds to the high-brightness mode.
        float[] nitsAutoBrightnessValues = toFloatArray(context.getResources().obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits));

        mHbmNits = nitsAutoBrightnessValues[nitsAutoBrightnessValues.length - 1];
        float[] hbmNitsRange = {nitsRange[0], mHbmNits};

        // This range only consists of the minimum and maximum backlight values, which only apply
        // in non-high-brightness mode.
        float[] normalizedBacklightRange = normalizeBacklightRange(
                context.getResources().getIntArray(
                        com.android.internal.R.array.config_screenBrightnessBacklight));

        mBacklightToNitsSpline = Spline.createSpline(normalizedBacklightRange, nitsRange);
        mNitsToHbmBacklightSpline = Spline.createSpline(hbmNitsRange, normalizedBacklightRange);
        mDefaultBrightness = obtainDefaultBrightness(context);

        // TODO(b/160025856): move to the "dump" method.
        Log.v(TAG, String.format("ctor | mNitsRange: [%f, %f]", nitsRange[0], nitsRange[1]));
        Log.v(TAG, String.format("ctor | mHbmNitsRange: [%f, %f]", hbmNitsRange[0],
                hbmNitsRange[1]));
        Log.v(TAG, String.format("ctor | mNormalizedBacklightRange: [%f, %f]",
                normalizedBacklightRange[0], normalizedBacklightRange[1]));

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());
        mIsOverlayShowing = false;
    }

    private void showUdfpsOverlay() {
        mHandler.post(() -> {
            if (!mIsOverlayShowing) {
                try {
                    Log.v(TAG, "showUdfpsOverlay | adding window");
                    mWindowManager.addView(mView, mLayoutParams);
                    mIsOverlayShowing = true;
                    mView.setOnTouchListener(mOnTouchListener);
                } catch (RuntimeException e) {
                    Log.e(TAG, "showUdfpsOverlay | failed to add window", e);
                }
            } else {
                Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
            }
        });
    }

    private void hideUdfpsOverlay() {
        mHandler.post(() -> {
            if (mIsOverlayShowing) {
                Log.v(TAG, "hideUdfpsOverlay | removing window");
                mView.setOnTouchListener(null);
                // Reset the controller back to its starting state.
                onFingerUp();
                mWindowManager.removeView(mView);
                mIsOverlayShowing = false;
            } else {
                Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
            }
        });
    }

    // Returns a value in the range of [0, 255].
    private int computeScrimOpacity() {
        // Backlight setting can be NaN, -1.0f, and [0.0f, 1.0f].
        float backlightSetting = Settings.System.getFloatForUser(mContentResolver,
                Settings.System.SCREEN_BRIGHTNESS_FLOAT, mDefaultBrightness,
                UserHandle.USER_CURRENT);

        // Constrain the backlight setting to [0.0f, 1.0f].
        float backlightValue = MathUtils.constrain(backlightSetting,
                PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX);

        // Interpolate the backlight value to nits.
        float nits = mBacklightToNitsSpline.interpolate(backlightValue);

        // Interpolate nits to a backlight value for a panel with enabled HBM.
        float interpolatedHbmBacklightValue = mNitsToHbmBacklightSpline.interpolate(nits);

        float gammaCorrectedHbmBacklightValue = (float) Math.pow(interpolatedHbmBacklightValue,
                1.0f / DISPLAY_GAMMA);
        float scrimOpacity = PowerManager.BRIGHTNESS_MAX - gammaCorrectedHbmBacklightValue;

        // Interpolate the opacity value from [0.0f, 1.0f] to [0, 255].
        return BrightnessSynchronizer.brightnessFloatToInt(scrimOpacity);
    }

    private void onFingerDown(int x, int y, float minor, float major) {
        mView.setScrimAlpha(computeScrimOpacity());
        mView.showScrimAndDot();
        try {
            FileWriter fw = new FileWriter(mHbmPath);
            fw.write(mHbmEnableCommand);
            fw.close();
            mFingerprintManager.onFingerDown(x, y, minor, major);
        } catch (IOException e) {
            mView.hideScrimAndDot();
            Log.e(TAG, "onFingerDown | failed to enable HBM: " + e.getMessage());
        }
    }

    private void onFingerUp() {
        mFingerprintManager.onFingerUp();
        // Hiding the scrim before disabling HBM results in less noticeable flicker.
        mView.hideScrimAndDot();
        try {
            FileWriter fw = new FileWriter(mHbmPath);
            fw.write(mHbmDisableCommand);
            fw.close();
        } catch (IOException e) {
            mView.showScrimAndDot();
            Log.e(TAG, "onFingerUp | failed to disable HBM: " + e.getMessage());
        }
    }

    private static WindowManager.LayoutParams createLayoutParams(Context context) {
        Point displaySize = new Point();
        context.getDisplay().getRealSize(displaySize);
        // TODO(b/160025856): move to the "dump" method.
        Log.v(TAG, "createLayoutParams | display size: " + displaySize.x + "x"
                + displaySize.y);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                displaySize.x,
                displaySize.y,
                // TODO(b/152419866): Use the UDFPS window type when it becomes available.
                WindowManager.LayoutParams.TYPE_BOOT_PROGRESS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle(TAG);
        lp.setFitInsetsTypes(0);
        return lp;
    }

    private static float obtainDefaultBrightness(Context context) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            Log.e(TAG, "PowerManager is unavailable. Can't obtain default brightness.");
            return 0f;
        }
        return MathUtils.constrain(powerManager.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT), PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX);
    }

    private static float[] toFloatArray(TypedArray array) {
        final int n = array.length();
        float[] vals = new float[n];
        for (int i = 0; i < n; i++) {
            vals[i] = array.getFloat(i, PowerManager.BRIGHTNESS_OFF_FLOAT);
        }
        array.recycle();
        return vals;
    }

    private static float[] normalizeBacklightRange(int[] backlight) {
        final int n = backlight.length;
        float[] normalizedBacklight = new float[n];
        for (int i = 0; i < n; i++) {
            normalizedBacklight[i] = BrightnessSynchronizer.brightnessIntToFloat(backlight[i]);
        }
        return normalizedBacklight;
    }
}
