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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class AuthBiometricFaceToFingerprintViewTest extends SysuiTestCase {

    @Mock AuthBiometricView.Callback mCallback;

    private AuthBiometricFaceToFingerprintView mFaceToFpView;

    @Mock private Button mNegativeButton;
    @Mock private Button mCancelButton;
    @Mock private Button mConfirmButton;
    @Mock private Button mUseCredentialButton;
    @Mock private Button mTryAgainButton;

    @Mock private TextView mTitleView;
    @Mock private TextView mSubtitleView;
    @Mock private TextView mDescriptionView;
    @Mock private TextView mIndicatorView;
    @Mock private ImageView mIconView;
    @Mock private View mIconHolderView;
    @Mock private AuthBiometricFaceView.IconController mIconController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFaceToFpView = new TestableView(mContext);
        mFaceToFpView.mIconController = mIconController;
        mFaceToFpView.setCallback(mCallback);

        mFaceToFpView.mNegativeButton = mNegativeButton;
        mFaceToFpView.mCancelButton = mCancelButton;
        mFaceToFpView.mUseCredentialButton = mUseCredentialButton;
        mFaceToFpView.mConfirmButton = mConfirmButton;
        mFaceToFpView.mTryAgainButton = mTryAgainButton;
        mFaceToFpView.mIndicatorView = mIndicatorView;
    }

    @Test
    public void testStateUpdated_whenDialogAnimatedIn() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));
    }

    @Test
    public void testIconUpdatesState_whenDialogStateUpdated() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));

        mFaceToFpView.updateState(AuthBiometricFaceView.STATE_AUTHENTICATED);
        verify(mFaceToFpView.mIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING),
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATED));

        assertEquals(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATED, mFaceToFpView.mState);
    }

    @Test
    public void testStateUpdated_whenSwitchToFingerprint() {
        mFaceToFpView.onDialogAnimatedIn();
        verify(mFaceToFpView.mIconController)
                .updateState(anyInt(), eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));

        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_ERROR);
        mFaceToFpView.updateState(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING);

        InOrder order = inOrder(mFaceToFpView.mIconController);
        order.verify(mFaceToFpView.mIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING),
                eq(AuthBiometricFaceToFingerprintView.STATE_ERROR));
        order.verify(mFaceToFpView.mIconController).updateState(
                eq(AuthBiometricFaceToFingerprintView.STATE_ERROR),
                eq(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING));

        verify(mConfirmButton).setVisibility(eq(View.GONE));
    }

    @Test
    public void testModeUpdated_whenSwitchToFingerprint() {
        mFaceToFpView.onDialogAnimatedIn();
        mFaceToFpView.onAuthenticationFailed(TYPE_FACE, "no face");
        waitForIdleSync();

        verify(mIndicatorView).setText(
                eq(mContext.getString(R.string.fingerprint_dialog_use_fingerprint_instead)));
        assertEquals(AuthBiometricFaceToFingerprintView.STATE_AUTHENTICATING, mFaceToFpView.mState);
    }

    public class TestableView extends AuthBiometricFaceToFingerprintView {
        public TestableView(Context context) {
            super(context, null, new MockInjector());
        }

        @Override
        protected int getDelayAfterAuthenticatedDurationMs() {
            return 0;
        }

        @Override
        protected IconController createUdfpsIconController() {
            return AuthBiometricFaceToFingerprintViewTest.this.mIconController;
        }
    }

    private class MockInjector extends AuthBiometricView.Injector {
        @Override
        public Button getNegativeButton() {
            return mNegativeButton;
        }

        @Override
        public Button getCancelButton() {
            return mCancelButton;
        }

        @Override
        public Button getUseCredentialButton() {
            return mUseCredentialButton;
        }

        @Override
        public Button getConfirmButton() {
            return mConfirmButton;
        }

        @Override
        public Button getTryAgainButton() {
            return mTryAgainButton;
        }

        @Override
        public TextView getTitleView() {
            return mTitleView;
        }

        @Override
        public TextView getSubtitleView() {
            return mSubtitleView;
        }

        @Override
        public TextView getDescriptionView() {
            return mDescriptionView;
        }

        @Override
        public TextView getIndicatorView() {
            return mIndicatorView;
        }

        @Override
        public ImageView getIconView() {
            return mIconView;
        }

        @Override
        public View getIconHolderView() {
            return mIconHolderView;
        }

        @Override
        public int getDelayAfterError() {
            return 0;
        }

        @Override
        public int getMediumToLargeAnimationDurationMs() {
            return 0;
        }
    }
}
