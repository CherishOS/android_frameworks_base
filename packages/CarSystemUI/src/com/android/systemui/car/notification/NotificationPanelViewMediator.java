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

package com.android.systemui.car.notification;

import android.car.hardware.power.CarPowerManager;
import android.content.res.Configuration;

import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.navigationbar.CarNavigationBarController;
import com.android.systemui.car.window.OverlayViewMediator;
import com.android.systemui.statusbar.policy.ConfigurationController;

import javax.inject.Inject;
import javax.inject.Singleton;

/** The view mediator which attaches the view controller to other elements of the system ui. */
@Singleton
public class NotificationPanelViewMediator implements OverlayViewMediator,
        ConfigurationController.ConfigurationListener {

    private final CarNavigationBarController mCarNavigationBarController;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final PowerManagerHelper mPowerManagerHelper;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final ConfigurationController mConfigurationController;

    @Inject
    public NotificationPanelViewMediator(
            CarNavigationBarController carNavigationBarController,
            NotificationPanelViewController notificationPanelViewController,

            PowerManagerHelper powerManagerHelper,

            CarDeviceProvisionedController carDeviceProvisionedController,
            ConfigurationController configurationController
    ) {
        mCarNavigationBarController = carNavigationBarController;
        mNotificationPanelViewController = notificationPanelViewController;
        mPowerManagerHelper = powerManagerHelper;
        mCarDeviceProvisionedController = carDeviceProvisionedController;
        mConfigurationController = configurationController;
    }

    @Override
    public void registerListeners() {
        mCarNavigationBarController.registerTopBarTouchListener(
                mNotificationPanelViewController.getDragOpenTouchListener());
        mCarNavigationBarController.registerBottomBarTouchListener(
                mNotificationPanelViewController.getDragCloseTouchListener());
        mCarNavigationBarController.registerLeftBarTouchListener(
                mNotificationPanelViewController.getDragCloseTouchListener());
        mCarNavigationBarController.registerRightBarTouchListener(
                mNotificationPanelViewController.getDragCloseTouchListener());

        mCarNavigationBarController.registerNotificationController(
                new CarNavigationBarController.NotificationsShadeController() {
                    @Override
                    public void togglePanel() {
                        mNotificationPanelViewController.toggle();
                    }

                    @Override
                    public boolean isNotificationPanelOpen() {
                        return mNotificationPanelViewController.isPanelExpanded();
                    }
                });
    }

    @Override
    public void setupOverlayContentViewControllers() {
        mNotificationPanelViewController.setOnUnseenCountUpdateListener(unseenNotificationCount -> {
            boolean hasUnseen = unseenNotificationCount > 0;
            mCarNavigationBarController.toggleAllNotificationsUnseenIndicator(
                    mCarDeviceProvisionedController.isCurrentUserFullySetup(), hasUnseen);
        });

        mPowerManagerHelper.setCarPowerStateListener(state -> {
            if (state == CarPowerManager.CarPowerStateListener.ON) {
                mNotificationPanelViewController.onCarPowerStateOn();
            }
        });
        mPowerManagerHelper.connectToCarService();

        mConfigurationController.addCallback(this);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        // No op.
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        registerListeners();
    }

    @Override
    public void onOverlayChanged() {
        // No op.
    }

    @Override
    public void onUiModeChanged() {
        // No op.
    }

    @Override
    public void onThemeChanged() {
        // No op.
    }

    @Override
    public void onLocaleListChanged() {
        mNotificationPanelViewController.reinflate();
        registerListeners();
    }
}
