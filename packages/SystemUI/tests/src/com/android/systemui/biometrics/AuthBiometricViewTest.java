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

import static android.hardware.biometrics.BiometricManager.Authenticators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthBiometricViewTest extends SysuiTestCase {

    @Mock private AuthBiometricView.Callback mCallback;
    @Mock private AuthPanelController mPanelController;

    @Mock private Button mNegativeButton;
    @Mock private Button mCancelButton;
    @Mock private Button mUseCredentialButton;

    @Mock private Button mPositiveButton;
    @Mock private Button mTryAgainButton;

    @Mock private TextView mTitleView;
    @Mock private TextView mSubtitleView;
    @Mock private TextView mDescriptionView;
    @Mock private TextView mIndicatorView;
    @Mock private ImageView mIconView;
    @Mock private View mIconHolderView;

    private TestableBiometricView mBiometricView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testOnAuthenticationSucceeded_noConfirmationRequired_sendsActionAuthenticated() {
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector());

        // The onAuthenticated runnable is posted when authentication succeeds.
        mBiometricView.onAuthenticationSucceeded();
        waitForIdleSync();
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED);
    }

    @Test
    public void testOnAuthenticationSucceeded_confirmationRequired_updatesDialogContents() {
        final Button negativeButton = new Button(mContext);
        final Button cancelButton = new Button(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return negativeButton;
            }

            @Override
            public Button getCancelButton() {
                return cancelButton;
            }
        });

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded();
        waitForIdleSync();
        assertEquals(AuthBiometricView.STATE_PENDING_CONFIRMATION, mBiometricView.mState);
        verify(mCallback, never()).onAction(anyInt());

        assertEquals(View.GONE, negativeButton.getVisibility());
        assertEquals(View.VISIBLE, cancelButton.getVisibility());
        assertTrue(cancelButton.isEnabled());

        verify(mBiometricView.mConfirmButton).setEnabled(eq(true));
        verify(mIndicatorView).setText(eq(R.string.biometric_dialog_tap_confirm));
        verify(mIndicatorView).setVisibility(eq(View.VISIBLE));
    }

    @Test
    public void testPositiveButton_sendsActionAuthenticated() {
        Button button = new Button(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
           @Override
            public Button getConfirmButton() {
               return button;
           }
        });

        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
    }

    @Test
    public void testNegativeButton_beforeAuthentication_sendsActionButtonNegative() {
        Button button = new Button(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return button;
            }
        });

        mBiometricView.onDialogAnimatedIn();
        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE);
    }

    @Test
    public void testCancelButton_whenPendingConfirmation_sendsActionUserCanceled() {
        Button cancelButton = new Button(mContext);
        Button negativeButton = new Button(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return negativeButton;
            }
            @Override
            public Button getCancelButton() {
                return cancelButton;
            }
        });

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded();

        assertEquals(View.GONE, negativeButton.getVisibility());

        cancelButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_USER_CANCELED);
    }

    @Test
    public void testTryAgainButton_sendsActionTryAgain() {
        Button button = new Button(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getTryAgainButton() {
                return button;
            }
        });

        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATING, mBiometricView.mState);
    }

    @Test
    public void testError_sendsActionError() {
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector());
        final String testError = "testError";
        mBiometricView.onError(testError);
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_ERROR);
        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
    }

    @Test
    public void testBackgroundClicked_sendsActionUserCanceled() {
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector());

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_afterAuthenticated_neverSendsUserCanceled() {
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector());

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        mBiometricView.onAuthenticationSucceeded();
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_whenSmallDialog_neverSendsUserCanceled() {
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector());
        mBiometricView.mLayoutParams = new AuthDialog.LayoutParams(0, 0);
        mBiometricView.updateSize(AuthDialog.SIZE_SMALL);

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testRestoresState() {
        final boolean requireConfirmation = true; // set/init from AuthController

        Button tryAgainButton = new Button(mContext);
        TextView indicatorView = new TextView(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getTryAgainButton() {
                return tryAgainButton;
            }
            @Override
            public TextView getIndicatorView() {
                return indicatorView;
            }
        });

        final String failureMessage = "testFailureMessage";
        mBiometricView.setRequireConfirmation(requireConfirmation);
        mBiometricView.onAuthenticationFailed(failureMessage);
        waitForIdleSync();

        Bundle state = new Bundle();
        mBiometricView.onSaveState(state);

        assertEquals(View.VISIBLE, tryAgainButton.getVisibility());
        assertEquals(View.VISIBLE, state.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY));

        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(AuthBiometricView.STATE_ERROR, state.getInt(AuthDialog.KEY_BIOMETRIC_STATE));

        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());
        assertTrue(state.getBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING));

        assertEquals(failureMessage, mBiometricView.mIndicatorView.getText());
        assertEquals(failureMessage, state.getString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING));

        // TODO: Test dialog size. Should move requireConfirmation to buildBiometricPromptBundle

        // Create new dialog and restore the previous state into it
        Button tryAgainButton2 = new Button(mContext);
        TextView indicatorView2 = new TextView(mContext);
        initDialog(mContext, false /* allowDeviceCredential */, mCallback, state,
                new MockInjector() {
                    @Override
                    public Button getTryAgainButton() {
                        return tryAgainButton2;
                    }

                    @Override
                    public TextView getIndicatorView() {
                        return indicatorView2;
                    }
                });
        mBiometricView.setRequireConfirmation(requireConfirmation);
        waitForIdleSync();

        // Test restored state
        assertEquals(View.VISIBLE, tryAgainButton.getVisibility());
        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());

        // TODO: Test restored text. Currently cannot test this, since it gets restored only after
        // dialog size is known.
    }

    @Test
    public void testCredentialButton_whenDeviceCredentialAllowed() {
        final Button negativeButton = new Button(mContext);
        final Button useCredentialButton = new Button(mContext);
        initDialog(mContext, true /* allowDeviceCredential */, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return negativeButton;
            }

            @Override
            public Button getUseCredentialButton() {
                return useCredentialButton;
            }
        });

        assertEquals(View.GONE, negativeButton.getVisibility());
        useCredentialButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL);
    }

    @Test
    public void testUdfpsBottomSpacerHeightForPortrait() {
        final int displayHeightPx = 3000;
        final int navbarHeightPx = 10;
        final int dialogBottomMarginPx = 20;
        final int buttonBarHeightPx = 100;
        final int textIndicatorHeightPx = 200;

        final int sensorLocationX = 540;
        final int sensorLocationY = 1600;
        final int sensorRadius = 100;
        final FingerprintSensorPropertiesInternal props = new FingerprintSensorPropertiesInternal(
                0 /* sensorId */, SensorProperties.STRENGTH_STRONG, 5 /* maxEnrollmentsPerUser */,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */, sensorLocationX, sensorLocationY,
                sensorRadius);

        assertEquals(970,
                AuthBiometricUdfpsView.calculateBottomSpacerHeightForPortrait(
                        props, displayHeightPx, textIndicatorHeightPx, buttonBarHeightPx,
                        dialogBottomMarginPx, navbarHeightPx
                ));
    }

    @Test
    public void testUdfpsBottomSpacerHeightForLandscape() {
        final int titleHeightPx = 320;
        final int subtitleHeightPx = 240;
        final int descriptionHeightPx = 200;
        final int topSpacerHeightPx = 550;
        final int textIndicatorHeightPx = 190;
        final int buttonBarHeightPx = 160;
        final int navbarBottomInsetPx = 75;

        assertEquals(885,
                AuthBiometricUdfpsView.calculateBottomSpacerHeightForLandscape(
                        titleHeightPx, subtitleHeightPx, descriptionHeightPx, topSpacerHeightPx,
                        textIndicatorHeightPx, buttonBarHeightPx, navbarBottomInsetPx));
    }

    @Test
    public void testUdfpsHorizontalSpacerWidthForLandscape() {
        final int displayWidthPx = 3000;
        final int dialogMarginPx = 20;
        final int navbarHorizontalInsetPx = 75;

        final int sensorLocationX = 540;
        final int sensorLocationY = 1600;
        final int sensorRadius = 100;
        final FingerprintSensorPropertiesInternal props = new FingerprintSensorPropertiesInternal(
                0 /* sensorId */, SensorProperties.STRENGTH_STRONG, 5 /* maxEnrollmentsPerUser */,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */, sensorLocationX, sensorLocationY,
                sensorRadius);

        assertEquals(1205,
                AuthBiometricUdfpsView.calculateHorizontalSpacerWidthForLandscape(
                        props, displayWidthPx, dialogMarginPx, navbarHorizontalInsetPx));
    }

    private PromptInfo buildPromptInfo(boolean allowDeviceCredential) {
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setTitle("Title");
        int authenticators = Authenticators.BIOMETRIC_WEAK;
        if (allowDeviceCredential) {
            authenticators |= Authenticators.DEVICE_CREDENTIAL;
        } else {
            promptInfo.setNegativeButtonText("Negative");
        }
        promptInfo.setAuthenticators(authenticators);
        return promptInfo;
    }

    private void initDialog(Context context, boolean allowDeviceCredential,
            AuthBiometricView.Callback callback,
            Bundle savedState, MockInjector injector) {
        mBiometricView = new TestableBiometricView(context, null, injector);
        mBiometricView.setPromptInfo(buildPromptInfo(allowDeviceCredential));
        mBiometricView.setCallback(callback);
        mBiometricView.restoreState(savedState);
        mBiometricView.onFinishInflateInternal();
        mBiometricView.onAttachedToWindowInternal();

        mBiometricView.setPanelController(mPanelController);
    }

    private void initDialog(Context context, boolean allowDeviceCredential,
            AuthBiometricView.Callback callback, MockInjector injector) {
        initDialog(context, allowDeviceCredential, callback, null /* savedState */, injector);
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
            return mPositiveButton;
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
            return 0; // Keep this at 0 for tests to invoke callback immediately.
        }

        @Override
        public int getMediumToLargeAnimationDurationMs() {
            return 0;
        }
    }

    private class TestableBiometricView extends AuthBiometricView {
        TestableBiometricView(Context context, AttributeSet attrs,
                Injector injector) {
            super(context, attrs, injector);
        }

        @Override
        protected int getDelayAfterAuthenticatedDurationMs() {
            return 0; // Keep this at 0 for tests to invoke callback immediately.
        }

        @Override
        protected int getStateForAfterError() {
            return 0;
        }

        @Override
        protected void handleResetAfterError() {

        }

        @Override
        protected void handleResetAfterHelp() {

        }

        @Override
        protected boolean supportsSmallDialog() {
            return false;
        }
    }
}
