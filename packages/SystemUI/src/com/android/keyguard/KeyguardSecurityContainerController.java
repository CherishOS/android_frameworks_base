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

import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_BIOMETRIC;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_EXTENDED_ACCESS;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_NONE_SECURITY;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_PASSWORD;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_SIM;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_PRIMARY;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_SECONDARY_USER;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_WORK_PROFILE;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityContainer.BouncerUiEvent;
import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityContainer.SwipeListener;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** Controller for {@link KeyguardSecurityContainer} */
@KeyguardBouncerScope
public class KeyguardSecurityContainerController extends ViewController<KeyguardSecurityContainer>
        implements KeyguardSecurityView {

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardSecurityView";

    private final AdminSecondaryLockScreenController mAdminSecondaryLockScreenController;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardSecurityModel mSecurityModel;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardSecurityViewFlipperController mSecurityViewFlipperController;
    private final List<KeyguardInputViewController> mChildren = new ArrayList<>();

    private SecurityCallback mSecurityCallback;
    private SecurityMode mCurrentSecurityMode = SecurityMode.Invalid;

    private KeyguardSecurityCallback mKeyguardSecurityCallback = new KeyguardSecurityCallback() {
        public void userActivity() {
            if (mSecurityCallback != null) {
                mSecurityCallback.userActivity();
            }
        }

        @Override
        public void onUserInput() {
            mUpdateMonitor.cancelFaceAuth();
        }

        @Override
        public void dismiss(boolean authenticated, int targetId) {
            dismiss(authenticated, targetId, /* bypassSecondaryLockScreen */ false);
        }

        @Override
        public void dismiss(boolean authenticated, int targetId,
                boolean bypassSecondaryLockScreen) {
            mSecurityCallback.dismiss(authenticated, targetId, bypassSecondaryLockScreen);
        }

        public boolean isVerifyUnlockOnly() {
            return false;
        }

        public void reportUnlockAttempt(int userId, boolean success, int timeoutMs) {
            if (success) {
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED,
                        SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__RESULT__SUCCESS);
                mLockPatternUtils.reportSuccessfulPasswordAttempt(userId);
                // Force a garbage collection in an attempt to erase any lockscreen password left in
                // memory. Do it asynchronously with a 5-sec delay to avoid making the keyguard
                // dismiss animation janky.
                ThreadUtils.postOnBackgroundThread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) { }
                    Runtime.getRuntime().gc();
                });
            } else {
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED,
                        SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__RESULT__FAILURE);
                reportFailedUnlockAttempt(userId, timeoutMs);
            }
            mMetricsLogger.write(new LogMaker(MetricsEvent.BOUNCER)
                    .setType(success ? MetricsEvent.TYPE_SUCCESS : MetricsEvent.TYPE_FAILURE));
            mUiEventLogger.log(success ? BouncerUiEvent.BOUNCER_PASSWORD_SUCCESS
                    : BouncerUiEvent.BOUNCER_PASSWORD_FAILURE);
        }

        public void reset() {
            mSecurityCallback.reset();
        }

        public void onCancelClicked() {
            mSecurityCallback.onCancelClicked();
        }
    };

    // The following is used to ignore callbacks from SecurityViews that are no longer current
    // (e.g. face unlock). This avoids unwanted asynchronous events from messing with the
    // state for the current security method.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {
        @Override
        public void userActivity() { }
        @Override
        public void reportUnlockAttempt(int userId, boolean success, int timeoutMs) { }
        @Override
        public boolean isVerifyUnlockOnly() {
            return false;
        }
        @Override
        public void dismiss(boolean securityVerified, int targetUserId) { }
        @Override
        public void dismiss(boolean authenticated, int targetId,
                boolean bypassSecondaryLockScreen) { }
        @Override
        public void onUserInput() { }
        @Override
        public void reset() {}
    };

    private SwipeListener mSwipeListener = new SwipeListener() {
        @Override
        public void onSwipeUp() {
            if (!mUpdateMonitor.isFaceDetectionRunning()) {
                mUpdateMonitor.requestFaceAuth();
                mKeyguardSecurityCallback.userActivity();
                showMessage(null, null);
            }
        }
    };

    @Inject
    KeyguardSecurityContainerController(KeyguardSecurityContainer view,
            AdminSecondaryLockScreenController.Factory adminSecondaryLockScreenControllerFactory,
            LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardSecurityModel keyguardSecurityModel,
            MetricsLogger metricsLogger,
            UiEventLogger uiEventLogger,
            KeyguardStateController keyguardStateController,
            KeyguardSecurityViewFlipperController securityViewFlipperController) {
        super(view);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = keyguardUpdateMonitor;
        mSecurityModel = keyguardSecurityModel;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mKeyguardStateController = keyguardStateController;
        mSecurityViewFlipperController = securityViewFlipperController;
        view.setLockPatternUtils(mLockPatternUtils);
        mAdminSecondaryLockScreenController = adminSecondaryLockScreenControllerFactory.create(
                mKeyguardSecurityCallback);
    }

    @Override
    public void init() {
        super.init();
    }

    @Override
    protected void onViewAttached() {
        mView.setSwipeListener(mSwipeListener);
    }

    @Override
    protected void onViewDetached() {
    }

    /** */
    public void onPause() {
        mAdminSecondaryLockScreenController.hide();
        if (mCurrentSecurityMode != SecurityMode.None) {
            getSecurityView(mCurrentSecurityMode).onPause();
        }
        mView.onPause();
    }


    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    public void showPrimarySecurityScreen(boolean turningOff) {
        SecurityMode securityMode = whitelistIpcs(() -> mSecurityModel.getSecurityMode(
                KeyguardUpdateMonitor.getCurrentUser()));
        if (DEBUG) Log.v(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ")");
        showSecurityScreen(securityMode);
    }

    @Override
    public void showPromptReason(int reason) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            if (reason != PROMPT_REASON_NONE) {
                Log.i(TAG, "Strong auth required, reason: " + reason);
            }
            getSecurityView(mCurrentSecurityMode).showPromptReason(reason);
        }
    }

    public void showMessage(CharSequence message, ColorStateList colorState) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getSecurityView(mCurrentSecurityMode).showMessage(message, colorState);
        }
    }

    public SecurityMode getCurrentSecurityMode() {
        return mCurrentSecurityMode;
    }

    public void dismiss(boolean authenticated, int targetUserId) {
        mKeyguardSecurityCallback.dismiss(authenticated, targetUserId);
    }

    public void reset() {
        mView.reset();
        mSecurityViewFlipperController.reset();
    }

    public CharSequence getTitle() {
        return mView.getTitle();
    }

    @Override
    public void onResume(int reason) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getSecurityView(mCurrentSecurityMode).onResume(reason);
        }
        mView.onResume(
                mSecurityModel.getSecurityMode(KeyguardUpdateMonitor.getCurrentUser()),
                mKeyguardStateController.isFaceAuthEnabled());
    }

    public void startAppearAnimation() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getSecurityView(mCurrentSecurityMode).startAppearAnimation();
        }
    }

    public boolean startDisappearAnimation(Runnable onFinishRunnable) {
        mView.startDisappearAnimation(getCurrentSecurityMode());

        if (mCurrentSecurityMode != SecurityMode.None) {
            return getSecurityView(mCurrentSecurityMode).startDisappearAnimation(
                    onFinishRunnable);
        }

        return false;
    }

    public void onStartingToHide() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getSecurityView(mCurrentSecurityMode).onStartingToHide();
        }
    }

    public void setSecurityCallback(SecurityCallback securityCallback) {
        mSecurityCallback = securityCallback;
    }

    /**
     * Shows the next security screen if there is one.
     * @param authenticated true if the user entered the correct authentication
     * @param targetUserId a user that needs to be the foreground user at the finish (if called)
     *     completion.
     * @param bypassSecondaryLockScreen true if the user is allowed to bypass the secondary
     *     secondary lock screen requirement, if any.
     * @return true if keyguard is done
     */
    public boolean showNextSecurityScreenOrFinish(boolean authenticated, int targetUserId,
            boolean bypassSecondaryLockScreen) {

        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish(" + authenticated + ")");
        boolean finish = false;
        boolean strongAuth = false;
        int eventSubtype = -1;
        BouncerUiEvent uiEvent = BouncerUiEvent.UNKNOWN;
        if (mUpdateMonitor.getUserHasTrust(targetUserId)) {
            finish = true;
            eventSubtype = BOUNCER_DISMISS_EXTENDED_ACCESS;
            uiEvent = BouncerUiEvent.BOUNCER_DISMISS_EXTENDED_ACCESS;
        } else if (mUpdateMonitor.getUserUnlockedWithBiometric(targetUserId)) {
            finish = true;
            eventSubtype = BOUNCER_DISMISS_BIOMETRIC;
            uiEvent = BouncerUiEvent.BOUNCER_DISMISS_BIOMETRIC;
        } else if (SecurityMode.None == getCurrentSecurityMode()) {
            SecurityMode securityMode = mSecurityModel.getSecurityMode(targetUserId);
            if (SecurityMode.None == securityMode) {
                finish = true; // no security required
                eventSubtype = BOUNCER_DISMISS_NONE_SECURITY;
                uiEvent = BouncerUiEvent.BOUNCER_DISMISS_NONE_SECURITY;
            } else {
                showSecurityScreen(securityMode); // switch to the alternate security view
            }
        } else if (authenticated) {
            switch (getCurrentSecurityMode()) {
                case Pattern:
                case Password:
                case PIN:
                    strongAuth = true;
                    finish = true;
                    eventSubtype = BOUNCER_DISMISS_PASSWORD;
                    uiEvent = BouncerUiEvent.BOUNCER_DISMISS_PASSWORD;
                    break;

                case SimPin:
                case SimPuk:
                    // Shortcut for SIM PIN/PUK to go to directly to user's security screen or home
                    SecurityMode securityMode = mSecurityModel.getSecurityMode(targetUserId);
                    if (securityMode == SecurityMode.None && mLockPatternUtils.isLockScreenDisabled(
                            KeyguardUpdateMonitor.getCurrentUser())) {
                        finish = true;
                        eventSubtype = BOUNCER_DISMISS_SIM;
                        uiEvent = BouncerUiEvent.BOUNCER_DISMISS_SIM;
                    } else {
                        showSecurityScreen(securityMode);
                    }
                    break;

                default:
                    Log.v(TAG, "Bad security screen " + getCurrentSecurityMode()
                            + ", fail safe");
                    showPrimarySecurityScreen(false);
                    break;
            }
        }
        // Check for device admin specified additional security measures.
        if (finish && !bypassSecondaryLockScreen) {
            Intent secondaryLockscreenIntent =
                    mUpdateMonitor.getSecondaryLockscreenRequirement(targetUserId);
            if (secondaryLockscreenIntent != null) {
                mAdminSecondaryLockScreenController.show(secondaryLockscreenIntent);
                return false;
            }
        }
        if (eventSubtype != -1) {
            mMetricsLogger.write(new LogMaker(MetricsProto.MetricsEvent.BOUNCER)
                    .setType(MetricsProto.MetricsEvent.TYPE_DISMISS).setSubtype(eventSubtype));
        }
        if (uiEvent != BouncerUiEvent.UNKNOWN) {
            mUiEventLogger.log(uiEvent);
        }
        if (finish) {
            mSecurityCallback.finish(strongAuth, targetUserId);
        }
        return finish;
    }

    public boolean needsInput() {
        return mSecurityViewFlipperController.needsInput();
    }

    /**
     * Switches to the given security view unless it's already being shown, in which case
     * this is a no-op.
     *
     * @param securityMode
     */
    @VisibleForTesting
    void showSecurityScreen(SecurityMode securityMode) {
        if (DEBUG) Log.d(TAG, "showSecurityScreen(" + securityMode + ")");

        if (securityMode == SecurityMode.Invalid || securityMode == mCurrentSecurityMode) {
            return;
        }

        KeyguardInputViewController oldView = getSecurityView(mCurrentSecurityMode);
        KeyguardInputViewController newView = getSecurityView(securityMode);

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
            oldView.setKeyguardCallback(mNullCallback); // ignore requests from old view
        }
        if (newView != null) {
            newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
            newView.setKeyguardCallback(mKeyguardSecurityCallback);
            mSecurityViewFlipperController.show(newView);
        }

        mCurrentSecurityMode = securityMode;
        mSecurityCallback.onSecurityModeChanged(
                securityMode, newView != null && newView.needsInput());
    }

    public void reportFailedUnlockAttempt(int userId, int timeoutMs) {
        // +1 for this time
        final int failedAttempts = mLockPatternUtils.getCurrentFailedPasswordAttempts(userId) + 1;

        if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts);

        final DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        final int failedAttemptsBeforeWipe =
                dpm.getMaximumFailedPasswordsForWipe(null, userId);

        final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0
                ? (failedAttemptsBeforeWipe - failedAttempts)
                : Integer.MAX_VALUE; // because DPM returns 0 if no restriction
        if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
            // The user has installed a DevicePolicyManager that requests a user/profile to be wiped
            // N attempts. Once we get below the grace period, we post this dialog every time as a
            // clear warning until the deletion fires.
            // Check which profile has the strictest policy for failed password attempts
            final int expiringUser = dpm.getProfileWithMinimumFailedPasswordsForWipe(userId);
            int userType = USER_TYPE_PRIMARY;
            if (expiringUser == userId) {
                // TODO: http://b/23522538
                if (expiringUser != UserHandle.USER_SYSTEM) {
                    userType = USER_TYPE_SECONDARY_USER;
                }
            } else if (expiringUser != UserHandle.USER_NULL) {
                userType = USER_TYPE_WORK_PROFILE;
            } // If USER_NULL, which shouldn't happen, leave it as USER_TYPE_PRIMARY
            if (remainingBeforeWipe > 0) {
                mView.showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe, userType);
            } else {
                // Too many attempts. The device will be wiped shortly.
                Slog.i(TAG, "Too many unlock attempts; user " + expiringUser + " will be wiped!");
                mView.showWipeDialog(failedAttempts, userType);
            }
        }
        mLockPatternUtils.reportFailedPasswordAttempt(userId);
        if (timeoutMs > 0) {
            mLockPatternUtils.reportPasswordLockout(timeoutMs, userId);
            mView.showTimeoutDialog(userId, timeoutMs, mLockPatternUtils,
                    mSecurityModel.getSecurityMode(userId));
        }
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        // no-op. This should never be reset.
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        // We already have one of these.
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mView.getCallback();
    }

    @Override
    public void showUsabilityHint() {
        mView.showUsabilityHint();
    }

    private KeyguardInputViewController getSecurityView(SecurityMode securityMode) {
        return mSecurityViewFlipperController
                .getSecurityView(securityMode, mKeyguardSecurityCallback);
    }
}
