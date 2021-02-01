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

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 *
 * Note that the current architecture is designed so that a single {@link UdfpsController}
 * controls/manages all UDFPS sensors. In other words, a single controller is registered with
 * {@link com.android.server.biometrics.sensors.fingerprint.FingerprintService}, and interfaces such
 * as {@link FingerprintManager#onPointerDown(int, int, int, float, float)} or
 * {@link IUdfpsOverlayController#showUdfpsOverlay(int)}should all have
 * {@code sensorId} parameters.
 */
@SuppressWarnings("deprecation")
@SysUISingleton
public class UdfpsController implements UdfpsView.HbmCallback, DozeReceiver {
    private static final String TAG = "UdfpsController";
    private static final long AOD_INTERRUPT_TIMEOUT_MILLIS = 1000;

    private final Context mContext;
    private final FingerprintManager mFingerprintManager;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    private final StatusBarStateController mStatusBarStateController;
    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting final FingerprintSensorPropertiesInternal mSensorProps;
    private final WindowManager.LayoutParams mCoreLayoutParams;
    private final UdfpsView mView;
    // Indicates whether the overlay is currently showing. Even if it has been requested, it might
    // not be showing.
    private boolean mIsOverlayShowing;
    // Indicates whether the overlay has been requested.
    private boolean mIsOverlayRequested;
    // Reason the overlay has been requested. See IUdfpsOverlayController for definitions.
    private int mRequestReason;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodTimeoutAction;

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(int sensorId, int reason) {
            UdfpsController.this.showOverlay(reason);
        }

        @Override
        public void hideUdfpsOverlay(int sensorId) {
            UdfpsController.this.hideOverlay();
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) {
            mView.onEnrollmentProgress(remaining);
        }

        @Override
        public void onEnrollmentHelp(int sensorId) {
            mView.onEnrollmentHelp();
        }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mView.setDebugMessage(message);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnTouchListener mOnTouchListener = (v, event) -> {
        UdfpsView view = (UdfpsView) v;
        final boolean isFingerDown = view.isIlluminationRequested();
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

    @Inject
    public UdfpsController(@NonNull Context context,
            @Main Resources resources,
            LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            WindowManager windowManager,
            @NonNull StatusBarStateController statusBarStateController,
            @Main DelayableExecutor fgExecutor,
            @NonNull ScrimController scrimController) {
        mContext = context;
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mStatusBarStateController = statusBarStateController;

        mSensorProps = findFirstUdfps();
        // At least one UDFPS sensor exists
        checkArgument(mSensorProps != null);

        mCoreLayoutParams = new WindowManager.LayoutParams(
                // TODO(b/152419866): Use the UDFPS window type when it becomes available.
                WindowManager.LayoutParams.TYPE_BOOT_PROGRESS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mCoreLayoutParams.setTitle(TAG);
        mCoreLayoutParams.setFitInsetsTypes(0);
        mCoreLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        mCoreLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mCoreLayoutParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        mView = (UdfpsView) inflater.inflate(R.layout.udfps_view, null, false);
        mView.setSensorProperties(mSensorProps);
        mView.setHbmCallback(this);

        scrimController.addScrimChangedListener(mView);
        statusBarStateController.addCallback(mView);

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());
        mIsOverlayShowing = false;
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstUdfps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnyUdfpsType()) {
                return props;
            }
        }
        return null;
    }

    @Override
    public void dozeTimeTick() {
        mView.dozeTimeTick();
    }

    /**
     * @return where the UDFPS exists on the screen in pixels.
     */
    public RectF getSensorLocation() {
        return mView.getSensorRect();
    }

    private void showOverlay(int reason) {
        if (mIsOverlayRequested) {
            return;
        }
        mIsOverlayRequested = true;
        mRequestReason = reason;
        updateOverlay();
    }

    private void hideOverlay() {
        if (!mIsOverlayRequested) {
            return;
        }
        mIsOverlayRequested = false;
        mRequestReason = IUdfpsOverlayController.REASON_UNKNOWN;
        updateOverlay();
    }

    private void updateOverlay() {
        if (mIsOverlayRequested) {
            showUdfpsOverlay(mRequestReason);
        } else {
            hideUdfpsOverlay();
        }
    }

    private WindowManager.LayoutParams computeLayoutParams() {
        // Default dimensions assume portrait mode.
        mCoreLayoutParams.x = mSensorProps.sensorLocationX - mSensorProps.sensorRadius;
        mCoreLayoutParams.y = mSensorProps.sensorLocationY - mSensorProps.sensorRadius;
        mCoreLayoutParams.height = 2 * mSensorProps.sensorRadius;
        mCoreLayoutParams.width = 2 * mSensorProps.sensorRadius;

        Point p = new Point();
        // Gets the size based on the current rotation of the display.
        mContext.getDisplay().getRealSize(p);

        // Transform dimensions if the device is in landscape mode.
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                mCoreLayoutParams.x = mSensorProps.sensorLocationY - mSensorProps.sensorRadius;
                mCoreLayoutParams.y =
                        p.y - mSensorProps.sensorLocationX - mSensorProps.sensorRadius;
                break;

            case Surface.ROTATION_270:
                mCoreLayoutParams.x =
                        p.x - mSensorProps.sensorLocationY - mSensorProps.sensorRadius;
                mCoreLayoutParams.y = mSensorProps.sensorLocationX - mSensorProps.sensorRadius;
                break;

            default:
                // Do nothing to stay in portrait mode.
        }
        return mCoreLayoutParams;
    }

    void onConfigurationChanged() {
        // When the configuration changes it's almost always necessary to destroy and re-create
        // the overlay's window to pass it the new LayoutParams.
        // Hiding the overlay will destroy its window. It's safe to hide the overlay regardless
        // of whether it is already hidden.
        hideUdfpsOverlay();
        // If the overlay needs to be shown, this will re-create and show the overlay with the
        // updated LayoutParams. Otherwise, the overlay will remain hidden.
        updateOverlay();
    }

    private void showUdfpsOverlay(int reason) {
        mFgExecutor.execute(() -> {
            if (!mIsOverlayShowing) {
                try {
                    Log.v(TAG, "showUdfpsOverlay | adding window");
                    mView.setUdfpsAnimation(getUdfpsAnimationForReason(reason));
                    mWindowManager.addView(mView, computeLayoutParams());
                    mView.setOnTouchListener(mOnTouchListener);
                    mIsOverlayShowing = true;
                } catch (RuntimeException e) {
                    Log.e(TAG, "showUdfpsOverlay | failed to add window", e);
                }
            } else {
                Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
            }
        });
    }

    @Nullable
    private UdfpsAnimation getUdfpsAnimationForReason(int reason) {
        Log.d(TAG, "getUdfpsAnimationForReason: " + reason);
        switch (reason) {
            case IUdfpsOverlayController.REASON_ENROLL:
                return new UdfpsAnimationEnroll(mContext);
            case IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD:
                return new UdfpsAnimationKeyguard(mView, mContext, mStatusBarStateController);
            case IUdfpsOverlayController.REASON_AUTH_FPM_OTHER:
                return new UdfpsAnimationFpmOther(mContext);
            default:
                Log.d(TAG, "Animation for reason " + reason + " not supported yet");
                return null;
        }
    }

    private void hideUdfpsOverlay() {
        mFgExecutor.execute(() -> {
            if (mIsOverlayShowing) {
                Log.v(TAG, "hideUdfpsOverlay | removing window");
                mView.setUdfpsAnimation(null);
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

    /**
     * Request fingerprint scan.
     *
     * This is intended to be called in response to a sensor that triggers an AOD interrupt for the
     * fingerprint sensor.
     */
    void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mIsAodInterruptActive) {
            return;
        }
        mIsAodInterruptActive = true;
        // Since the sensor that triggers the AOD interrupt doesn't provide ACTION_UP/ACTION_CANCEL,
        // we need to be careful about not letting the screen accidentally remain in high brightness
        // mode. As a mitigation, queue a call to cancel the fingerprint scan.
        mCancelAodTimeoutAction = mFgExecutor.executeDelayed(this::onCancelAodInterrupt,
                AOD_INTERRUPT_TIMEOUT_MILLIS);
        // using a hard-coded value for major and minor until it is available from the sensor
        onFingerDown(screenX, screenY, minor, major);
    }

    /**
     * Cancel fingerprint scan.
     *
     * This is intended to be called after the fingerprint scan triggered by the AOD interrupt
     * either succeeds or fails.
     */
    void onCancelAodInterrupt() {
        if (!mIsAodInterruptActive) {
            return;
        }
        if (mCancelAodTimeoutAction != null) {
            mCancelAodTimeoutAction.run();
            mCancelAodTimeoutAction = null;
        }
        mIsAodInterruptActive = false;
        onFingerUp();
    }

    // This method can be called from the UI thread.
    private void onFingerDown(int x, int y, float minor, float major) {
        mView.setOnIlluminatedRunnable(
                () -> mFingerprintManager.onPointerDown(mSensorProps.sensorId, x, y, minor, major));
        mView.startIllumination();
    }

    // This method can be called from the UI thread.
    private void onFingerUp() {
        mFingerprintManager.onPointerUp(mSensorProps.sensorId);
        mView.stopIllumination();
    }

    @Override
    public void enableHbm(Surface surface) {
        // Do nothing. This method can be implemented for devices that require the high-brightness
        // mode for fingerprint illumination.
    }

    @Override
    public void disableHbm(Surface surface) {
        // Do nothing. This method can be implemented for devices that require the high-brightness
        // mode for fingerprint illumination.
    }
}
