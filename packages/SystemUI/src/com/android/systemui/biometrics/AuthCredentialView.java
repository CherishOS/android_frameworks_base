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

import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

/**
 * Abstract base class for Pin, Pattern, or Password authentication, for
 * {@link BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)}
 */
public abstract class AuthCredentialView extends LinearLayout {

    private static final String TAG = "BiometricPrompt/AuthCredentialView";
    private static final int ERROR_DURATION_MS = 3000;

    private final AccessibilityManager mAccessibilityManager;

    protected final Handler mHandler;

    private Bundle mBiometricPromptBundle;
    private AuthPanelController mPanelController;
    private boolean mShouldAnimatePanel;
    private boolean mShouldAnimateContents;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    protected TextView mErrorView;

    protected @Utils.CredentialType int mCredentialType;
    protected final LockPatternUtils mLockPatternUtils;
    protected AuthContainerView mContainerView;
    protected Callback mCallback;
    protected AsyncTask<?, ?, ?> mPendingLockCheck;
    protected int mUserId;
    protected ErrorTimer mErrorTimer;

    interface Callback {
        void onCredentialMatched();
    }

    protected static class ErrorTimer extends CountDownTimer {
        private final TextView mErrorView;
        private final Context mContext;

        /**
         * @param millisInFuture    The number of millis in the future from the call
         *                          to {@link #start()} until the countdown is done and {@link
         *                          #onFinish()}
         *                          is called.
         * @param countDownInterval The interval along the way to receive
         *                          {@link #onTick(long)} callbacks.
         */
        public ErrorTimer(Context context, long millisInFuture, long countDownInterval,
                TextView errorView) {
            super(millisInFuture, countDownInterval);
            mErrorView = errorView;
            mContext = context;
        }

        @Override
        public void onTick(long millisUntilFinished) {
            final int secondsCountdown = (int) (millisUntilFinished / 1000);
            mErrorView.setText(mContext.getString(
                    R.string.biometric_dialog_credential_too_many_attempts, secondsCountdown));
        }

        @Override
        public void onFinish() {
            mErrorView.setText("");
        }
    }

    protected final Runnable mClearErrorRunnable = new Runnable() {
        @Override
        public void run() {
            mErrorView.setText("");
        }
    };

    public AuthCredentialView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mLockPatternUtils = new LockPatternUtils(mContext);
        mHandler = new Handler(Looper.getMainLooper());
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
    }

    protected void showError(String error) {
        mHandler.removeCallbacks(mClearErrorRunnable);
        mErrorView.setText(error);
        mHandler.postDelayed(mClearErrorRunnable, ERROR_DURATION_MS);
    }

    private void setTextOrHide(TextView view, String string) {
        if (TextUtils.isEmpty(string)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(string);
        }

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    private void setText(TextView view, String string) {
        view.setText(string);
    }

    void setUser(int user) {
        mUserId = user;
    }

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    void setBiometricPromptBundle(Bundle bundle) {
        mBiometricPromptBundle = bundle;
    }

    void setPanelController(AuthPanelController panelController, boolean animatePanel) {
        mPanelController = panelController;
        mShouldAnimatePanel = animatePanel;
    }

    void setShouldAnimateContents(boolean animateContents) {
        mShouldAnimateContents = animateContents;
    }

    void setContainerView(AuthContainerView containerView) {
        mContainerView = containerView;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        setText(mTitleView, mBiometricPromptBundle.getString(BiometricPrompt.KEY_TITLE));
        setTextOrHide(mSubtitleView,
                mBiometricPromptBundle.getString(BiometricPrompt.KEY_SUBTITLE));
        setTextOrHide(mDescriptionView,
                mBiometricPromptBundle.getString(BiometricPrompt.KEY_DESCRIPTION));

        // Only animate this if we're transitioning from a biometric view.
        if (mShouldAnimateContents) {
            setTranslationY(getResources()
                    .getDimension(R.dimen.biometric_dialog_credential_translation_offset));
            setAlpha(0);

            postOnAnimation(() -> {
                animate().translationY(0)
                        .setDuration(AuthDialog.ANIMATE_CREDENTIAL_INITIAL_DURATION_MS)
                        .alpha(1.f)
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                        .withLayer()
                        .start();
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mErrorTimer != null) {
            mErrorTimer.cancel();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCredentialType = Utils.getCredentialType(mContext, mUserId);
        mTitleView = findViewById(R.id.title);
        mSubtitleView = findViewById(R.id.subtitle);
        mDescriptionView = findViewById(R.id.description);
        mErrorView = findViewById(R.id.error);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mShouldAnimatePanel) {
            // Credential view is always full screen.
            mPanelController.setUseFullScreen(true);
            mPanelController.updateForContentDimensions(mPanelController.getContainerWidth(),
                    mPanelController.getContainerHeight(), 0 /* animateDurationMs */);
            mShouldAnimatePanel = false;
        }
    }

    protected void onErrorTimeoutFinish() {}

    protected void onCredentialChecked(boolean matched, int timeoutMs) {
        if (matched) {
            mClearErrorRunnable.run();
            mCallback.onCredentialMatched();
        } else {
            if (timeoutMs > 0) {
                mHandler.removeCallbacks(mClearErrorRunnable);
                long deadline = mLockPatternUtils.setLockoutAttemptDeadline(mUserId, timeoutMs);
                mErrorTimer = new ErrorTimer(mContext,
                        deadline - SystemClock.elapsedRealtime(),
                        LockPatternUtils.FAILED_ATTEMPT_COUNTDOWN_INTERVAL_MS,
                        mErrorView) {
                    @Override
                    public void onFinish() {
                        onErrorTimeoutFinish();
                        mClearErrorRunnable.run();
                    }
                };
                mErrorTimer.start();
            } else {
                final int error;
                switch (mCredentialType) {
                    case Utils.CREDENTIAL_PIN:
                        error = R.string.biometric_dialog_wrong_pin;
                        break;
                    case Utils.CREDENTIAL_PATTERN:
                        error = R.string.biometric_dialog_wrong_pattern;
                        break;
                    case Utils.CREDENTIAL_PASSWORD:
                        error = R.string.biometric_dialog_wrong_password;
                        break;
                    default:
                        error = R.string.biometric_dialog_wrong_password;
                        break;
                }
                showError(getResources().getString(error));
            }
        }
    }
}
