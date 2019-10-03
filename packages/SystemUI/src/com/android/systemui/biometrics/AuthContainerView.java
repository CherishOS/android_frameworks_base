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

package com.android.systemui.biometrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Top level container/controller for the BiometricPrompt UI.
 */
public class AuthContainerView extends LinearLayout
        implements AuthDialog, WakefulnessLifecycle.Observer {

    private static final String TAG = "BiometricPrompt/AuthContainerView";
    private static final int ANIMATION_DURATION_SHOW_MS = 250;
    private static final int ANIMATION_DURATION_AWAY_MS = 350; // ms

    static final int STATE_UNKNOWN = 0;
    static final int STATE_ANIMATING_IN = 1;
    static final int STATE_PENDING_DISMISS = 2;
    static final int STATE_SHOWING = 3;
    static final int STATE_ANIMATING_OUT = 4;
    static final int STATE_GONE = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_UNKNOWN, STATE_ANIMATING_IN, STATE_PENDING_DISMISS, STATE_SHOWING,
            STATE_ANIMATING_OUT, STATE_GONE})
    @interface ContainerState {}

    final Config mConfig;
    private final IBinder mWindowToken = new Binder();
    private final WindowManager mWindowManager;
    private final AuthPanelController mPanelController;
    private final Interpolator mLinearOutSlowIn;
    @VisibleForTesting final BiometricCallback mBiometricCallback;
    private final CredentialCallback mCredentialCallback;

    @VisibleForTesting final FrameLayout mFrameLayout;
    private final AuthBiometricView mBiometricView;
    @VisibleForTesting AuthCredentialView mCredentialView;

    private final ImageView mBackgroundView;
    private final ScrollView mBiometricScrollView;
    private final View mPanelView;

    private final float mTranslationY;

    @VisibleForTesting final WakefulnessLifecycle mWakefulnessLifecycle;

    private @ContainerState int mContainerState = STATE_UNKNOWN;

    // Non-null only if the dialog is in the act of dismissing and has not sent the reason yet.
    @Nullable @AuthDialogCallback.DismissedReason Integer mPendingCallbackReason;

    static class Config {
        Context mContext;
        AuthDialogCallback mCallback;
        Bundle mBiometricPromptBundle;
        boolean mRequireConfirmation;
        int mUserId;
        String mOpPackageName;
        int mModalityMask;
        boolean mSkipIntro;
    }

    public static class Builder {
        Config mConfig;

        public Builder(Context context) {
            mConfig = new Config();
            mConfig.mContext = context;
        }

        public Builder setCallback(AuthDialogCallback callback) {
            mConfig.mCallback = callback;
            return this;
        }

        public Builder setBiometricPromptBundle(Bundle bundle) {
            mConfig.mBiometricPromptBundle = bundle;
            return this;
        }

        public Builder setRequireConfirmation(boolean requireConfirmation) {
            mConfig.mRequireConfirmation = requireConfirmation;
            return this;
        }

        public Builder setUserId(int userId) {
            mConfig.mUserId = userId;
            return this;
        }

        public Builder setOpPackageName(String opPackageName) {
            mConfig.mOpPackageName = opPackageName;
            return this;
        }

        public Builder setSkipIntro(boolean skip) {
            mConfig.mSkipIntro = skip;
            return this;
        }

        public AuthContainerView build(int modalityMask) {
            mConfig.mModalityMask = modalityMask;
            return new AuthContainerView(mConfig);
        }
    }

    @VisibleForTesting
    final class BiometricCallback implements AuthBiometricView.Callback {
        @Override
        public void onAction(int action) {
            switch (action) {
                case AuthBiometricView.Callback.ACTION_AUTHENTICATED:
                    animateAway(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED);
                    break;
                case AuthBiometricView.Callback.ACTION_USER_CANCELED:
                    animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
                    break;
                case AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE:
                    animateAway(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
                    break;
                case AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN:
                    mConfig.mCallback.onTryAgainPressed();
                    break;
                case AuthBiometricView.Callback.ACTION_ERROR:
                    animateAway(AuthDialogCallback.DISMISSED_ERROR);
                    break;
                case AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL:
                    mConfig.mCallback.onDeviceCredentialPressed();
                    showCredentialView();
                    break;
                default:
                    Log.e(TAG, "Unhandled action: " + action);
            }
        }
    }

    final class CredentialCallback implements AuthCredentialView.Callback {
        @Override
        public void onCredentialMatched() {
            animateAway(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED);
        }
    }

    @VisibleForTesting
    AuthContainerView(Config config) {
        super(config.mContext);

        mConfig = config;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);

        mTranslationY = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mBiometricCallback = new BiometricCallback();
        mCredentialCallback = new CredentialCallback();

        final LayoutInflater factory = LayoutInflater.from(mContext);
        mFrameLayout = (FrameLayout) factory.inflate(
                R.layout.auth_container_view, this, false /* attachToRoot */);

        mPanelView = mFrameLayout.findViewById(R.id.panel);
        mPanelController = new AuthPanelController(mContext, mPanelView);

        // TODO: Update with new controllers if multi-modal authentication can occur simultaneously
        if (config.mModalityMask == BiometricAuthenticator.TYPE_FINGERPRINT) {
            mBiometricView = (AuthBiometricFingerprintView)
                    factory.inflate(R.layout.auth_biometric_fingerprint_view, null, false);
        } else if (config.mModalityMask == BiometricAuthenticator.TYPE_FACE) {
            mBiometricView = (AuthBiometricFaceView)
                    factory.inflate(R.layout.auth_biometric_face_view, null, false);
        } else {
            Log.e(TAG, "Unsupported modality mask: " + config.mModalityMask);
            mBiometricView = null;
            mBackgroundView = null;
            mBiometricScrollView = null;
            return;
        }

        mBackgroundView = mFrameLayout.findViewById(R.id.background);

        UserManager userManager = mContext.getSystemService(UserManager.class);
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (userManager.isManagedProfile(mConfig.mUserId)) {
            final Drawable image = getResources().getDrawable(R.drawable.work_challenge_background,
                    mContext.getTheme());
            image.setColorFilter(dpm.getOrganizationColorForUser(mConfig.mUserId),
                    PorterDuff.Mode.DARKEN);
            mBackgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mBackgroundView.setImageDrawable(image);
        }

        mBiometricView.setRequireConfirmation(mConfig.mRequireConfirmation);
        mBiometricView.setPanelController(mPanelController);
        mBiometricView.setBiometricPromptBundle(config.mBiometricPromptBundle);
        mBiometricView.setCallback(mBiometricCallback);
        mBiometricView.setBackgroundView(mBackgroundView);
        mBiometricView.setUserId(mConfig.mUserId);

        mBiometricScrollView = mFrameLayout.findViewById(R.id.biometric_scrollview);
        mBiometricScrollView.addView(mBiometricView);
        addView(mFrameLayout);

        setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            }
            return true;
        });

        setFocusableInTouchMode(true);
        requestFocus();
    }

    private void showCredentialView() {
        final LayoutInflater factory = LayoutInflater.from(mContext);
        mCredentialView = (AuthCredentialView) factory.inflate(
                R.layout.auth_credential_view, null, false);
        mCredentialView.setUser(mConfig.mUserId);
        mCredentialView.setCallback(mCredentialCallback);
        mCredentialView.setBiometricPromptBundle(mConfig.mBiometricPromptBundle);
        mFrameLayout.addView(mCredentialView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mPanelController.setContainerDimensions(getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mWakefulnessLifecycle.addObserver(this);

        if (mConfig.mSkipIntro) {
            mContainerState = STATE_SHOWING;
        } else {
            mContainerState = STATE_ANIMATING_IN;
            // The background panel and content are different views since we need to be able to
            // animate them separately in other places.
            mPanelView.setY(mTranslationY);
            mBiometricScrollView.setY(mTranslationY);

            setAlpha(0f);
            postOnAnimation(() -> {
                mPanelView.animate()
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .withEndAction(this::onDialogAnimatedIn)
                        .start();
                mBiometricScrollView.animate()
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                animate()
                        .alpha(1f)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            });
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWakefulnessLifecycle.removeObserver(this);
    }

    @Override
    public void onStartedGoingToSleep() {
        animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    @Override
    public void show(WindowManager wm, @Nullable Bundle savedState) {
        mBiometricView.restoreState(savedState);
        wm.addView(this, getLayoutParams(mWindowToken));
    }

    @Override
    public void dismissWithoutCallback(boolean animate) {
        if (animate) {
            animateAway(false /* sendReason */, 0 /* reason */);
        } else {
            removeWindowIfAttached();
        }
    }

    @Override
    public void dismissFromSystemServer() {
        removeWindowIfAttached();
    }

    @Override
    public void onAuthenticationSucceeded() {
        mBiometricView.onAuthenticationSucceeded();
    }

    @Override
    public void onAuthenticationFailed(String failureReason) {
        mBiometricView.onAuthenticationFailed(failureReason);
    }

    @Override
    public void onHelp(String help) {
        mBiometricView.onHelp(help);
    }

    @Override
    public void onError(String error) {
        mBiometricView.onError(error);
    }

    @Override
    public void onSaveState(@NonNull Bundle outState) {
        outState.putInt(AuthDialog.KEY_CONTAINER_STATE, mContainerState);
        mBiometricView.onSaveState(outState);
    }

    @Override
    public String getOpPackageName() {
        return mConfig.mOpPackageName;
    }

    @VisibleForTesting
    void animateAway(int reason) {
        animateAway(true /* sendReason */, reason);
    }

    private void animateAway(boolean sendReason, @AuthDialogCallback.DismissedReason int reason) {
        if (mContainerState == STATE_ANIMATING_IN) {
            Log.w(TAG, "startDismiss(): waiting for onDialogAnimatedIn");
            mContainerState = STATE_PENDING_DISMISS;
            return;
        }

        if (mContainerState == STATE_ANIMATING_OUT) {
            Log.w(TAG, "Already dismissing, sendReason: " + sendReason + " reason: " + reason);
            return;
        }
        mContainerState = STATE_ANIMATING_OUT;

        if (sendReason) {
            mPendingCallbackReason = reason;
        } else {
            mPendingCallbackReason = null;
        }

        final Runnable endActionRunnable = () -> {
            setVisibility(View.INVISIBLE);
            removeWindowIfAttached();
        };

        postOnAnimation(() -> {
            mPanelView.animate()
                    .translationY(mTranslationY)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .withEndAction(endActionRunnable)
                    .start();
            mBiometricScrollView.animate()
                    .translationY(mTranslationY)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
            if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                mCredentialView.animate()
                        .translationY(mTranslationY)
                        .setDuration(ANIMATION_DURATION_AWAY_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            }
            animate()
                    .alpha(0f)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
        });
    }

    private boolean isDeviceCredentialAllowed() {
        return mConfig.mBiometricPromptBundle.getBoolean(
                BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, false);
    }

    private void sendPendingCallbackIfNotNull() {
        Log.d(TAG, "pendingCallback: " + mPendingCallbackReason);
        if (mPendingCallbackReason != null) {
            mConfig.mCallback.onDismissed(mPendingCallbackReason);
            mPendingCallbackReason = null;
        }
    }

    private void removeWindowIfAttached() {
        sendPendingCallbackIfNotNull();

        if (mContainerState == STATE_GONE) {
            return;
        }
        mContainerState = STATE_GONE;
        mWindowManager.removeView(this);
    }

    private void onDialogAnimatedIn() {
        if (mContainerState == STATE_PENDING_DISMISS) {
            Log.d(TAG, "onDialogAnimatedIn(): mPendingDismissDialog=true, dismissing now");
            animateAway(false /* sendReason */, 0);
            return;
        }
        mContainerState = STATE_SHOWING;
        mBiometricView.onDialogAnimatedIn();
    }

    /**
     * @param windowToken token for the window
     * @return
     */
    public static WindowManager.LayoutParams getLayoutParams(IBinder windowToken) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("BiometricPrompt");
        lp.token = windowToken;
        return lp;
    }
}
