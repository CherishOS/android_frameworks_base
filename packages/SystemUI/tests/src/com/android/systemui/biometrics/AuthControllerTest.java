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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.TestCase.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.Authenticator;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.face.FaceManager;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthControllerTest extends SysuiTestCase {

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IBiometricServiceReceiverInternal mReceiver;
    @Mock
    private AuthDialog mDialog1;
    @Mock
    private AuthDialog mDialog2;

    private TestableAuthController mAuthController;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        TestableContext context = spy(mContext);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));

        when(context.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);

        when(mDialog1.getOpPackageName()).thenReturn("Dialog1");
        when(mDialog2.getOpPackageName()).thenReturn("Dialog2");

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);
        when(mDialog2.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController = new TestableAuthController(context, new MockInjector());
        mAuthController.mComponents = mContext.getComponents();

        mAuthController.start();
    }

    // Callback tests

    @Test
    public void testSendsReasonUserCanceled_whenDismissedByUserCancel() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
    }

    @Test
    public void testSendsReasonNegative_whenDismissedByButtonNegative() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
    }

    @Test
    public void testSendsReasonConfirmed_whenDismissedByButtonPositive() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED);
    }

    @Test
    public void testSendsReasonConfirmNotRequired_whenDismissedByAuthenticated() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED);
        verify(mReceiver).onDialogDismissed(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED);
    }

    @Test
    public void testSendsReasonError_whenDismissedByError() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_ERROR);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_ERROR);
    }

    @Test
    public void testSendsReasonServerRequested_whenDismissedByServer() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED);
    }

    @Test
    public void testSendsReasonCredentialConfirmed_whenDeviceCredentialAuthenticated()
            throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED);
    }

    // Statusbar tests

    @Test
    public void testShowInvoked_whenSystemRequested()
            throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());
    }

    @Test
    public void testOnAuthenticationSucceededInvoked_whenSystemRequested() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onBiometricAuthenticated();
        verify(mDialog1).onAuthenticationSucceeded();
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricRejected() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_NONE,
                BiometricConstants.BIOMETRIC_PAUSED_REJECTED,
                0 /* vendorCode */);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(captor.capture());

        assertEquals(captor.getValue(), mContext.getString(R.string.biometric_not_recognized));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricTimedOut() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = BiometricConstants.BIOMETRIC_ERROR_TIMEOUT;
        final int vendorCode = 0;
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(captor.capture());

        assertEquals(captor.getValue(), FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testOnHelpInvoked_whenSystemRequested() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final String helpMessage = "help";
        mAuthController.onBiometricHelp(helpMessage);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onHelp(captor.capture());

        assertEquals(captor.getValue(), helpMessage);
    }

    @Test
    public void testOnErrorInvoked_whenSystemRequested() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = 1;
        final int vendorCode = 0;
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onError(captor.capture());

        assertEquals(captor.getValue(), FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testErrorLockout_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockout_whenCredentialNotAllowed_sendsOnError() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1).onError(eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialNotAllowed_sendsOnError() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1).onError(eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testDismissWithoutCallbackInvoked_whenSystemRequested() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.hideAuthenticationDialog();
        verify(mDialog1).dismissFromSystemServer();
    }

    @Test
    public void testClientNotified_whenDismissedBySystemServer() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        mAuthController.hideAuthenticationDialog();
        verify(mDialog1).dismissFromSystemServer();

        assertNotNull(mAuthController.mCurrentDialog);
        assertNotNull(mAuthController.mReceiver);
    }

    // Corner case tests

    @Test
    public void testShowNewDialog_beforeOldDialogDismissed_SkipsAnimations() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());

        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);

        // First dialog should be dismissed without animation
        verify(mDialog1).dismissWithoutCallback(eq(false) /* animate */);

        // Second dialog should be shown without animation
        verify(mDialog2).show(any(), any());
    }

    @Test
    public void testConfigurationPersists_whenOnConfigurationChanged() {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());

        // Return that the UI is in "showing" state
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Bundle savedState = (Bundle) args[0];
            savedState.putInt(
                    AuthDialog.KEY_CONTAINER_STATE, AuthContainerView.STATE_SHOWING);
            return null; // onSaveState returns void
        }).when(mDialog1).onSaveState(any());

        mAuthController.onConfigurationChanged(new Configuration());

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog1).onSaveState(captor.capture());

        // Old dialog doesn't animate
        verify(mDialog1).dismissWithoutCallback(eq(false /* animate */));

        // Saved state is restored into new dialog
        ArgumentCaptor<Bundle> captor2 = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog2).show(any(), captor2.capture());

        // TODO: This should check all values we want to save/restore
        assertEquals(captor.getValue(), captor2.getValue());
    }

    @Test
    public void testConfigurationPersists_whenBiometricFallbackToCredential() {
        showDialog(Authenticator.TYPE_CREDENTIAL | Authenticator.TYPE_BIOMETRIC,
                BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());

        // Pretend that the UI is now showing device credential UI.
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Bundle savedState = (Bundle) args[0];
            savedState.putInt(
                    AuthDialog.KEY_CONTAINER_STATE, AuthContainerView.STATE_SHOWING);
            savedState.putBoolean(AuthDialog.KEY_CREDENTIAL_SHOWING, true);
            return null; // onSaveState returns void
        }).when(mDialog1).onSaveState(any());

        mAuthController.onConfigurationChanged(new Configuration());

        // Check that the new dialog was initialized to the credential UI.
        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog2).show(any(), captor.capture());
        assertEquals(Authenticator.TYPE_CREDENTIAL,
                mAuthController.mLastBiometricPromptBundle
                        .getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
    }

    @Test
    public void testClientNotified_whenTaskStackChangesDuringAuthentication() throws Exception {
        showDialog(Authenticator.TYPE_BIOMETRIC, BiometricPrompt.TYPE_FACE);

        List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();
        ActivityManager.RunningTaskInfo taskInfo = mock(ActivityManager.RunningTaskInfo.class);
        taskInfo.topActivity = mock(ComponentName.class);
        when(taskInfo.topActivity.getPackageName()).thenReturn("other_package");
        tasks.add(taskInfo);
        when(mAuthController.mActivityTaskManager.getTasks(anyInt())).thenReturn(tasks);

        mAuthController.mTaskStackListener.onTaskStackChanged();
        waitForIdleSync();

        assertNull(mAuthController.mCurrentDialog);
        assertNull(mAuthController.mReceiver);
        verify(mDialog1).dismissWithoutCallback(true /* animate */);
        verify(mReceiver).onDialogDismissed(eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL));
    }

    // Helpers

    private void showDialog(int authenticators, int biometricModality) {
        mAuthController.showAuthenticationDialog(createTestDialogBundle(authenticators),
                mReceiver /* receiver */,
                biometricModality,
                true /* requireConfirmation */,
                0 /* userId */,
                "testPackage");
    }

    private Bundle createTestDialogBundle(int authenticators) {
        Bundle bundle = new Bundle();

        bundle.putCharSequence(BiometricPrompt.KEY_TITLE, "Title");
        bundle.putCharSequence(BiometricPrompt.KEY_SUBTITLE, "Subtitle");
        bundle.putCharSequence(BiometricPrompt.KEY_DESCRIPTION, "Description");
        bundle.putCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT, "Negative Button");

        // RequireConfirmation is a hint to BiometricService. This can be forced to be required
        // by user settings, and should be tested in BiometricService.
        bundle.putBoolean(BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true);

        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);

        return bundle;
    }

    private final class TestableAuthController extends AuthController {
        private int mBuildCount = 0;
        private Bundle mLastBiometricPromptBundle;

        TestableAuthController(Context context, Injector injector) {
            super(context, injector);
        }

        @Override
        protected AuthDialog buildDialog(Bundle biometricPromptBundle,
                boolean requireConfirmation, int userId, int type, String opPackageName,
                boolean skipIntro) {

            mLastBiometricPromptBundle = biometricPromptBundle;

            AuthDialog dialog;
            if (mBuildCount == 0) {
                dialog = mDialog1;
            } else if (mBuildCount == 1) {
                dialog = mDialog2;
            } else {
                dialog = null;
            }
            mBuildCount++;
            return dialog;
        }
    }

    private final class MockInjector extends AuthController.Injector {
        @Override
        IActivityTaskManager getActivityTaskManager() {
            return mock(IActivityTaskManager.class);
        }
    }
}

