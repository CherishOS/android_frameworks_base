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

package com.android.server.location.gnss;

import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.location.GnssNavigationMessage;
import android.location.IGnssNavigationMessageListener;
import android.location.util.identity.CallerIdentity;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;

/**
 * An base implementation for GPS navigation messages provider.
 * It abstracts out the responsibility of handling listeners, while still allowing technology
 * specific implementations to be built.
 *
 * @hide
 */
public class GnssNavigationMessageProvider extends
        GnssListenerMultiplexer<Void, IGnssNavigationMessageListener, Void> {

    private final GnssNavigationMessageProviderNative mNative;

    public GnssNavigationMessageProvider(UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper) {
        this(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper,
                new GnssNavigationMessageProviderNative());
    }

    @VisibleForTesting
    public GnssNavigationMessageProvider(UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper, GnssNavigationMessageProviderNative aNative) {
        super(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper);
        mNative = aNative;
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssNavigationMessageListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored) {
        Preconditions.checkState(mNative.isNavigationMessageSupported());

        if (mNative.startNavigationMessageCollection()) {
            if (D) {
                Log.d(TAG, "starting gnss navigation messages");
            }
            return true;
        } else {
            Log.e(TAG, "error starting gnss navigation messages");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mNative.isNavigationMessageSupported()) {
            if (mNative.stopNavigationMessageCollection()) {
                if (D) {
                    Log.d(TAG, "stopping gnss navigation messages");
                }
            } else {
                Log.e(TAG, "error stopping gnss navigation messages");
            }
        }
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onNavigationMessageAvailable(GnssNavigationMessage event) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteLocationAccess(registration.getIdentity(), PERMISSION_FINE)) {
                return listener -> listener.onGnssNavigationMessageReceived(event);
            } else {
                return null;
            }
        });
    }

    @Override
    protected boolean isServiceSupported() {
        return mNative.isNavigationMessageSupported();
    }

    @VisibleForTesting
    static class GnssNavigationMessageProviderNative {
        boolean isNavigationMessageSupported() {
            return native_is_navigation_message_supported();
        }

        boolean startNavigationMessageCollection() {
            return native_start_navigation_message_collection();
        }

        boolean stopNavigationMessageCollection() {
            return native_stop_navigation_message_collection();
        }
    }

    static native boolean native_is_navigation_message_supported();

    static native boolean native_start_navigation_message_collection();

    static native boolean native_stop_navigation_message_collection();
}
