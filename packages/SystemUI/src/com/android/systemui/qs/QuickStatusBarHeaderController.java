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

package com.android.systemui.qs;

import android.app.AlarmManager.AlarmClockInfo;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyChipEvent;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.qs.carrier.QSCarrierGroupController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeController.Callback;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller for {@link QuickStatusBarHeader}.
 */
class QuickStatusBarHeaderController extends ViewController<QuickStatusBarHeader> {
    private static final String TAG = "QuickStatusBarHeader";

    private final ZenModeController mZenModeController;
    private final NextAlarmController mNextAlarmController;
    private final PrivacyItemController mPrivacyItemController;
    private final RingerModeTracker mRingerModeTracker;
    private final ActivityStarter mActivityStarter;
    private final UiEventLogger mUiEventLogger;
    private final QSCarrierGroupController mQSCarrierGroupController;
    private final QuickQSPanel mHeaderQsPanel;
    private final LifecycleRegistry mLifecycle;
    private final OngoingPrivacyChip mPrivacyChip;
    private final Clock mClockView;
    private final View mNextAlarmContainer;
    private final View mRingerContainer;
    private final QSTileHost mQSTileHost;
    private final StatusBarIconController mStatusBarIconController;
    private final CommandQueue mCommandQueue;
    private final StatusIconContainer mIconContainer;
    private final StatusBarIconController.TintedIconManager mIconManager;

    private boolean mListening;
    private AlarmClockInfo mNextAlarm;
    private boolean mAllIndicatorsEnabled;
    private boolean mMicCameraIndicatorsEnabled;
    private boolean mPrivacyChipLogged;
    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;

    private final ZenModeController.Callback mZenModeControllerCallback = new Callback() {
        @Override
        public void onZenChanged(int zen) {
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger());
        }

        @Override
        public void onConfigChanged(ZenModeConfig config) {
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger());
        }
    };

    private final NextAlarmChangeCallback mNextAlarmChangeCallback = new NextAlarmChangeCallback() {
        @Override
        public void onNextAlarmChanged(AlarmClockInfo nextAlarm) {
            mNextAlarm = nextAlarm;
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger());
        }
    };

    private final LifecycleOwner mLifecycleOwner = new LifecycleOwner() {
        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mLifecycle;
        }
    };

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void onPrivacyItemsChanged(@NonNull List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }

        @Override
        public void onFlagAllChanged(boolean flag) {
            if (mAllIndicatorsEnabled != flag) {
                mAllIndicatorsEnabled = flag;
                update();
            }
        }

        @Override
        public void onFlagMicCameraChanged(boolean flag) {
            if (mMicCameraIndicatorsEnabled != flag) {
                mMicCameraIndicatorsEnabled = flag;
                update();
            }
        }

        private void update() {
            StatusIconContainer iconContainer = mView.requireViewById(R.id.statusIcons);
            iconContainer.setIgnoredSlots(getIgnoredIconSlots());
            setChipVisibility(!mPrivacyChip.getPrivacyList().isEmpty());
        }
    };

    private View.OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v == mClockView) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
                if (mNextAlarm.getShowIntent() != null) {
                    mActivityStarter.postStartActivityDismissingKeyguard(
                            mNextAlarm.getShowIntent());
                } else {
                    Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                    mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                            AlarmClock.ACTION_SHOW_ALARMS), 0);
                }
            } else if (v == mPrivacyChip) {
                // If the privacy chip is visible, it means there were some indicators
                Handler mUiHandler = new Handler(Looper.getMainLooper());
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_CLICK);
                mUiHandler.post(() -> {
                    mActivityStarter.postStartActivityDismissingKeyguard(
                            new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                    mQSTileHost.collapsePanels();
                });
            } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        Settings.ACTION_SOUND_SETTINGS), 0);
            }
        }
    };

    private QuickStatusBarHeaderController(QuickStatusBarHeader view,
            ZenModeController zenModeController, NextAlarmController nextAlarmController,
            PrivacyItemController privacyItemController, RingerModeTracker ringerModeTracker,
            ActivityStarter activityStarter, UiEventLogger uiEventLogger,
            QSTileHost qsTileHost, StatusBarIconController statusBarIconController,
            CommandQueue commandQueue,
            QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder) {
        super(view);
        mZenModeController = zenModeController;
        mNextAlarmController = nextAlarmController;
        mPrivacyItemController = privacyItemController;
        mRingerModeTracker = ringerModeTracker;
        mActivityStarter = activityStarter;
        mUiEventLogger = uiEventLogger;
        mQSTileHost = qsTileHost;
        mStatusBarIconController = statusBarIconController;
        mCommandQueue = commandQueue;
        mLifecycle = new LifecycleRegistry(mLifecycleOwner);

        mQSCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(mView.findViewById(R.id.carrier_group))
                .build();


        mPrivacyChip = mView.findViewById(R.id.privacy_chip);
        mHeaderQsPanel = mView.findViewById(R.id.quick_qs_panel);
        mNextAlarmContainer = mView.findViewById(R.id.alarm_container);
        mClockView = mView.findViewById(R.id.clock);
        mRingerContainer = mView.findViewById(R.id.ringer_container);
        mIconContainer = mView.findViewById(R.id.statusIcons);

        mIconManager = new StatusBarIconController.TintedIconManager(mIconContainer, mCommandQueue);
    }

    @Override
    protected void onViewAttached() {
        mRingerModeTracker.getRingerModeInternal().observe(mLifecycleOwner, ringer -> {
            mRingerMode = ringer;
            mView.updateStatusText(mRingerMode, mNextAlarm, isZenOverridingRinger());
        });

        mClockView.setOnClickListener(mOnClickListener);
        mNextAlarmContainer.setOnClickListener(mOnClickListener);
        mRingerContainer.setOnClickListener(mOnClickListener);
        mPrivacyChip.setOnClickListener(mOnClickListener);

        // Ignore privacy icons because they show in the space above QQS
        mIconContainer.addIgnoredSlots(getIgnoredIconSlots());
        mIconContainer.setShouldRestrictIcons(false);
        mStatusBarIconController.addIconGroup(mIconManager);

        mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
        mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();

        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);

        mView.onAttach(mIconManager);
    }

    @Override
    protected void onViewDetached() {
        mRingerModeTracker.getRingerModeInternal().removeObservers(mLifecycleOwner);
        mClockView.setOnClickListener(null);
        mNextAlarmContainer.setOnClickListener(null);
        mRingerContainer.setOnClickListener(null);
        mPrivacyChip.setOnClickListener(null);
        mStatusBarIconController.removeIconGroup(mIconManager);
        setListening(false);
    }

    public void setListening(boolean listening) {
        mQSCarrierGroupController.setListening(listening);

        if (listening == mListening) {
            return;
        }
        mListening = listening;

        mHeaderQsPanel.setListening(listening);
        if (mHeaderQsPanel.switchTileLayout()) {
            mView.updateResources();
        }

        if (listening) {
            mZenModeController.addCallback(mZenModeControllerCallback);
            mNextAlarmController.addCallback(mNextAlarmChangeCallback);
            mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
            // Get the most up to date info
            mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
            mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mZenModeController.removeCallback(mZenModeControllerCallback);
            mNextAlarmController.removeCallback(mNextAlarmChangeCallback);
            mLifecycle.setCurrentState(Lifecycle.State.CREATED);
            mPrivacyItemController.removeCallback(mPICCallback);
            mPrivacyChipLogged = false;
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && getChipEnabled()) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_VIEW);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        if (getChipEnabled()) {
            ignored.add(mView.getResources().getString(
                    com.android.internal.R.string.status_bar_camera));
            ignored.add(mView.getResources().getString(
                    com.android.internal.R.string.status_bar_microphone));
            if (mAllIndicatorsEnabled) {
                ignored.add(mView.getResources().getString(
                        com.android.internal.R.string.status_bar_location));
            }
        }

        return ignored;
    }

    private boolean getChipEnabled() {
        return mMicCameraIndicatorsEnabled || mAllIndicatorsEnabled;
    }

    private boolean isZenOverridingRinger() {
        return ZenModeConfig.isZenOverridingRinger(mZenModeController.getZen(),
                mZenModeController.getConsolidatedPolicy());
    }

    static class Builder {
        private final ZenModeController mZenModeController;
        private final NextAlarmController mNextAlarmController;
        private final PrivacyItemController mPrivacyItemController;
        private final RingerModeTracker mRingerModeTracker;
        private final ActivityStarter mActivityStarter;
        private final UiEventLogger mUiEventLogger;
        private final QSTileHost mQsTileHost;
        private final StatusBarIconController mStatusBarIconController;
        private final CommandQueue mCommandQueue;
        private final QSCarrierGroupController.Builder mQSCarrierGroupControllerBuilder;
        private QuickStatusBarHeader mView;

        @Inject
        Builder(ZenModeController zenModeController, NextAlarmController nextAlarmController,
                PrivacyItemController privacyItemController, RingerModeTracker ringerModeTracker,
                ActivityStarter activityStarter, UiEventLogger uiEventLogger, QSTileHost qsTileHost,
                StatusBarIconController statusBarIconController, CommandQueue commandQueue,
                QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder) {
            mZenModeController = zenModeController;
            mNextAlarmController = nextAlarmController;
            mPrivacyItemController = privacyItemController;
            mRingerModeTracker = ringerModeTracker;
            mActivityStarter = activityStarter;
            mUiEventLogger = uiEventLogger;
            mQsTileHost = qsTileHost;
            mStatusBarIconController = statusBarIconController;
            mCommandQueue = commandQueue;
            mQSCarrierGroupControllerBuilder = qsCarrierGroupControllerBuilder;
        }

        public Builder setQuickStatusBarHeader(QuickStatusBarHeader view) {
            mView = view;
            return this;
        }


        QuickStatusBarHeaderController build() {
            return new QuickStatusBarHeaderController(mView, mZenModeController,
                    mNextAlarmController, mPrivacyItemController, mRingerModeTracker,
                    mActivityStarter, mUiEventLogger, mQsTileHost, mStatusBarIconController,
                    mCommandQueue, mQSCarrierGroupControllerBuilder);
        }
    }
}
