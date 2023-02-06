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

package com.android.keyguard;

import static com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.DEFAULT_PIN_LENGTH;

import android.view.View;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.policy.DevicePostureController;

public class KeyguardPinViewController
        extends KeyguardPinBasedInputViewController<KeyguardPINView> {
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback = posture ->
            mView.onDevicePostureChanged(posture);
    private LockPatternUtils mLockPatternUtils;
    private final FeatureFlags mFeatureFlags;
    private View mOkButton = mView.findViewById(R.id.key_enter);

    private int mUserId;
    private long mPinLength;

    protected KeyguardPinViewController(KeyguardPINView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector,
            DevicePostureController postureController,
            FeatureFlags featureFlags) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPostureController = postureController;
        mLockPatternUtils = lockPatternUtils;
        mFeatureFlags = featureFlags;
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                getKeyguardSecurityCallback().reset();
                getKeyguardSecurityCallback().onCancelClicked();
            });
        }
        mPasswordEntry.setUserActivityListener(this::onUserInput);
        mPostureController.addCallback(mPostureCallback);
    }

    protected void onUserInput() {
        super.onUserInput();
        if (isAutoConfirmation()) {
            if (mPasswordEntry.getText().length() == mPinLength) {
                verifyPasswordAndUnlock();
            }
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPostureController.removeCallback(mPostureCallback);
    }

    @Override
    public void startAppearAnimation() {
        if (mFeatureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)) {
            mUserId = KeyguardUpdateMonitor.getCurrentUser();
            mPinLength = mLockPatternUtils.getPinLength(mUserId);
            if (isAutoConfirmation()) {
                mOkButton.setVisibility(View.INVISIBLE);
            } else {
                mOkButton.setVisibility(View.VISIBLE);
            }
            mPasswordEntry.setIsPinHinting(true, isPinHinting());
        }
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    /*
        Responsible for identifying if PIN hinting is to be enabled or not
     */
    private boolean isPinHinting() {
        return mLockPatternUtils.getPinLength(mUserId) == DEFAULT_PIN_LENGTH;
    }

    /*
        Responsible for identifying if auto confirm is enabled or not in Settings
     */
    private boolean isAutoConfirmation() {
        //Checks if user has enabled the auto confirm in Settings
        return mLockPatternUtils.isAutoPinConfirmEnabled(mUserId);
    }
}
