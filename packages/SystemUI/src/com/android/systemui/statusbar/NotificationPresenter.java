/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.systemui.statusbar;

import android.content.Intent;
import android.os.Handler;
import android.service.notification.NotificationListenerService;

import java.util.Set;

/**
 * An abstraction of something that presents notifications, e.g. StatusBar. Contains methods
 * for both querying the state of the system (some modularised piece of functionality may
 * want to act differently based on e.g. whether the presenter is visible to the user or not) and
 * for affecting the state of the system (e.g. starting an intent, given that the presenter may
 * want to perform some action before doing so).
 */
public interface NotificationPresenter extends NotificationUpdateHandler,
        NotificationData.Environment {

    /**
     * Returns true if the presenter is not visible. For example, it may not be necessary to do
     * animations if this returns true.
     */
    boolean isPresenterFullyCollapsed();

    /**
     * Returns true if the presenter is locked. For example, if the keyguard is active.
     */
    boolean isPresenterLocked();

    /**
     * Runs the given intent. The presenter may want to run some animations or close itself when
     * this happens.
     */
    void startNotificationGutsIntent(Intent intent, int appUid);

    /**
     * Returns NotificationData.
     */
    NotificationData getNotificationData();

    /**
     * Returns the Handler for NotificationPresenter.
     */
    Handler getHandler();

    // TODO: Create NotificationEntryManager and move this method to there.
    /**
     * Signals that some notifications have changed, and NotificationPresenter should update itself.
     */
    void updateNotifications();

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation);

    // TODO: Create NotificationEntryManager and move this method to there.
    /**
     * Gets the latest ranking map.
     */
    NotificationListenerService.RankingMap getLatestRankingMap();

    /**
     * Called when the locked status of the device is changed for a work profile.
     */
    void onWorkChallengeChanged();

    /**
     * Notifications in this set are kept around when they were canceled in response to a remote
     * input interaction. This allows us to show what you replied and allows you to continue typing
     * into it.
     */
    // TODO: Create NotificationEntryManager and move this method to there.
    Set<String> getKeysKeptForRemoteInput();

    /**
     * Called when the current user changes.
     * @param newUserId new user id
     */
    void onUserSwitched(int newUserId);

    /**
     * Gets the NotificationLockscreenUserManager for this Presenter.
     */
    NotificationLockscreenUserManager getNotificationLockscreenUserManager();
}
