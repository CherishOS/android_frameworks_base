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
 * limitations under the License
 */

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.os.SomeArgs;
import com.android.systemui.SystemUI;
import com.android.systemui.biometrics.ui.BiometricDialogView;
import com.android.systemui.statusbar.CommandQueue;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 */
public class BiometricDialogImpl extends SystemUI implements CommandQueue.Callbacks,
        DialogViewCallback {
    private static final String TAG = "BiometricDialogImpl";
    private static final boolean DEBUG = true;

    // TODO: These should just be saved from onSaveState
    private SomeArgs mCurrentDialogArgs;
    private BiometricDialog mCurrentDialog;

    private WindowManager mWindowManager;
    private IBiometricServiceReceiverInternal mReceiver;

    @Override
    public void onTryAgainPressed() {
        try {
            mReceiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    public void onDismissed(int reason) {
        switch (reason) {
            case DialogViewCallback.DISMISSED_USER_CANCELED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
                break;

            case DialogViewCallback.DISMISSED_BUTTON_NEGATIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
                break;

            case DialogViewCallback.DISMISSED_BUTTON_POSITIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CONFIRMED);
                break;

            case DialogViewCallback.DISMISSED_AUTHENTICATED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CONFIRM_NOT_REQUIRED);
                break;

            case DialogViewCallback.DISMISSED_ERROR:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_ERROR);
                break;
            default:
                Log.e(TAG, "Unhandled reason: " + reason);
                break;
        }
    }

    private void sendResultAndCleanUp(int result) {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(result);
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception", e);
        }
        onDialogDismissed();
    }

    @Override
    public void start() {
        final PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                || pm.hasSystemFeature(PackageManager.FEATURE_FACE)
                || pm.hasSystemFeature(PackageManager.FEATURE_IRIS)) {
            getComponent(CommandQueue.class).addCallback(this);
            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        }
    }

    @Override
    public void showBiometricDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver,
            int type, boolean requireConfirmation, int userId) {
        if (DEBUG) {
            Log.d(TAG, "showBiometricDialog, type: " + type
                    + ", requireConfirmation: " + requireConfirmation);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        args.argi1 = type;
        args.arg3 = requireConfirmation;
        args.argi2 = userId;

        boolean skipAnimation = false;
        if (mCurrentDialog != null) {
            Log.w(TAG, "mCurrentDialog: " + mCurrentDialog);
            skipAnimation = true;
        }
        showDialog(args, skipAnimation /* skipAnimation */, null /* savedState */);
    }

    @Override
    public void onBiometricAuthenticated(boolean authenticated, String failureReason) {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated: " + authenticated
                + " reason: " + failureReason);

        if (authenticated) {
            mCurrentDialog.onAuthenticationSucceeded();
        } else {
            mCurrentDialog.onAuthenticationFailed(failureReason);
        }
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);

        mCurrentDialog.onHelp(message);
    }

    @Override
    public void onBiometricError(String error) {
        if (DEBUG) Log.d(TAG, "onBiometricError: " + error);
        mCurrentDialog.onError(error);
    }

    @Override
    public void hideBiometricDialog() {
        if (DEBUG) Log.d(TAG, "hideBiometricDialog");

        // TODO: I think we need to remove this interface
        mCurrentDialog.dismissWithoutCallback(true /* animate */);
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;
        final int type = args.argi1;

        // Create a new dialog but do not replace the current one yet.
        final BiometricDialog newDialog = buildDialog(
                (Bundle) args.arg1 /* bundle */,
                (boolean) args.arg3 /* requireConfirmation */,
                args.argi2 /* userId */,
                type);

        if (newDialog == null) {
            Log.e(TAG, "Unsupported type: " + type);
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "showDialog, "
                    + " savedState: " + savedState
                    + " mCurrentDialog: " + mCurrentDialog
                    + " newDialog: " + newDialog
                    + " type: " + type);
        }

        if (savedState != null) {
            // SavedState is only non-null if it's from onConfigurationChanged. Restore the state
            // even though it may be removed / re-created again
            newDialog.restoreState(savedState);
        } else if (mCurrentDialog != null) {
            // If somehow we're asked to show a dialog, the old one doesn't need to be animated
            // away. This can happen if the app cancels and re-starts auth during configuration
            // change. This is ugly because we also have to do things on onConfigurationChanged
            // here.
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
        }

        mReceiver = (IBiometricServiceReceiverInternal) args.arg2;
        mCurrentDialog = newDialog;
        mCurrentDialog.show(mWindowManager, skipAnimation);
    }

    private void onDialogDismissed() {
        if (DEBUG) Log.d(TAG, "onDialogDismissed");
        if (mCurrentDialog == null) {
            Log.w(TAG, "Dialog already dismissed");
        }
        mReceiver = null;
        mCurrentDialog = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Save the state of the current dialog (buttons showing, etc)
        if (mCurrentDialog != null) {
            final Bundle savedState = new Bundle();
            mCurrentDialog.onSaveState(savedState);
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
            mCurrentDialog = null;

            showDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState);
        }
    }

    protected BiometricDialog buildDialog(Bundle biometricPromptBundle,
            boolean requireConfirmation, int userId, int type) {
        return new BiometricDialogView.Builder(mContext)
                .setCallback(this)
                .setBiometricPromptBundle(biometricPromptBundle)
                .setRequireConfirmation(requireConfirmation)
                .setUserId(userId)
                .build(type);
    }
}
