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

package com.android.systemui.qs.tiles;

import static android.provider.Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quicksettings.Tile;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/** Quick settings tile: Quick access wallet **/
public class QuickAccessWalletTile extends QSTileImpl<QSTile.State> {

    private static final String FEATURE_CHROME_OS = "org.chromium.arc";
    private final CharSequence mLabel = mContext.getString(R.string.wallet_title);
    // TODO(b/180959290): Re-create the QAW Client when the default NFC payment app changes.
    private final QuickAccessWalletClient mQuickAccessWalletClient;
    private final KeyguardStateController mKeyguardStateController;
    private final PackageManager mPackageManager;
    private final SecureSettings mSecureSettings;
    private final FeatureFlags mFeatureFlags;

    @Inject
    public QuickAccessWalletTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            QuickAccessWalletClient quickAccessWalletClient,
            KeyguardStateController keyguardStateController,
            PackageManager packageManager,
            SecureSettings secureSettings,
            FeatureFlags featureFlags) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mQuickAccessWalletClient = quickAccessWalletClient;
        mKeyguardStateController = keyguardStateController;
        mPackageManager = packageManager;
        mSecureSettings = secureSettings;
        mFeatureFlags = featureFlags;
    }


    @Override
    public State newTileState() {
        State state = new State();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleClick() {
        mActivityStarter.postStartActivityDismissingKeyguard(
                mQuickAccessWalletClient.createWalletIntent(), /* delay= */ 0);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        CharSequence qawLabel = mQuickAccessWalletClient.getServiceLabel();
        state.label = qawLabel == null ? mLabel : qawLabel;
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_qs_wallet);
        boolean isDeviceLocked = !mKeyguardStateController.isUnlocked();
        if (mQuickAccessWalletClient.isWalletFeatureAvailable()) {
            state.state = isDeviceLocked ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE;
            state.secondaryLabel = isDeviceLocked
                    ? null
                    : mContext.getString(R.string.wallet_secondary_label);
            state.stateDescription = state.secondaryLabel;
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public boolean isAvailable() {
        return mFeatureFlags.isQuickAccessWalletEnabled()
                && mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                && !mPackageManager.hasSystemFeature(FEATURE_CHROME_OS)
                && mSecureSettings.getString(NFC_PAYMENT_DEFAULT_COMPONENT) != null;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        CharSequence qawLabel = mQuickAccessWalletClient.getServiceLabel();
        return qawLabel == null ? mLabel : qawLabel;
    }
}
