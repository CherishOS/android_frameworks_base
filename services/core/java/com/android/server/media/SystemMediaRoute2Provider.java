/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.media;

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;
import static android.media.MediaRoute2Info.FEATURE_LIVE_VIDEO;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRoutesInfo;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provides routes for local playbacks such as phone speaker, wired headset, or Bluetooth speakers.
 */
class SystemMediaRoute2Provider extends MediaRoute2Provider {
    private static final String TAG = "MR2SystemProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final String DEFAULT_ROUTE_ID = "DEFAULT_ROUTE";
    static final String SYSTEM_SESSION_ID = "SYSTEM_SESSION";

    private final AudioManager mAudioManager;
    private final IAudioService mAudioService;
    private final Handler mHandler;
    private final Context mContext;
    private final BluetoothRouteProvider mBtRouteProvider;

    private static ComponentName sComponentName = new ComponentName(
            SystemMediaRoute2Provider.class.getPackageName$(),
            SystemMediaRoute2Provider.class.getName());

    MediaRoute2Info mDefaultRoute;
    @NonNull List<MediaRoute2Info> mBluetoothRoutes = Collections.EMPTY_LIST;
    final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();

    final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
        @Override
        public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
            mHandler.post(new Runnable() {
                @Override public void run() {
                    updateAudioRoutes(newRoutes);
                }
            });
        }
    };

    SystemMediaRoute2Provider(Context context, Callback callback) {
        super(sComponentName);
        setCallback(callback);

        mIsSystemRouteProvider = true;

        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));

        initializeDefaultRoute();
        mBtRouteProvider = BluetoothRouteProvider.getInstance(context, (routes) -> {
            mBluetoothRoutes = routes;
            publishRoutes();

            boolean sessionInfoChanged;
            synchronized (mLock) {
                sessionInfoChanged = updateSessionInfosIfNeededLocked();
            }
            if (sessionInfoChanged) {
                notifySessionInfoUpdated();
            }
        });
        initializeSessionInfo();
    }

    @Override
    public void requestCreateSession(String packageName, String routeId, long requestId,
            Bundle sessionHints) {
        // Do nothing
    }

    @Override
    public void releaseSession(String sessionId) {
        // Do nothing
    }
    @Override
    public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
        // Do nothing
    }

    @Override
    public void selectRoute(String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void deselectRoute(String sessionId, String routeId) {
        // Do nothing since we don't support multiple BT yet.
    }

    @Override
    public void transferToRoute(String sessionId, String routeId) {
        if (TextUtils.equals(routeId, mDefaultRoute.getId())) {
            mBtRouteProvider.clearActiveDevices();
        } else {
            mBtRouteProvider.setActiveDevice(routeId);
        }
    }

    //TODO: implement method
    @Override
    public void sendControlRequest(@NonNull String routeId, @NonNull Intent request) {
    }

    //TODO: implement method
    @Override
    public void requestSetVolume(String routeId, int volume) {
    }

    private void initializeDefaultRoute() {
        mDefaultRoute = new MediaRoute2Info.Builder(
                DEFAULT_ROUTE_ID,
                mContext.getResources().getText(R.string.default_audio_route_name).toString())
                .setVolumeHandling(mAudioManager.isVolumeFixed()
                        ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                        : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .build();

        AudioRoutesInfo newAudioRoutes = null;
        try {
            newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
        } catch (RemoteException e) {
        }
        if (newAudioRoutes != null) {
            // This will select the active BT route if there is one and the current
            // selected route is the default system route, or if there is no selected
            // route yet.
            updateAudioRoutes(newAudioRoutes);
        }
    }

    private void initializeSessionInfo() {
        mBluetoothRoutes = mBtRouteProvider.getBluetoothRoutes();

        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();
        builder.addRoute(mDefaultRoute);
        for (MediaRoute2Info route : mBluetoothRoutes) {
            builder.addRoute(route);
        }
        setProviderState(builder.build());
        mHandler.post(() -> notifyProviderState());

        //TODO: clean up this
        // This is required because it is not instantiated in the main thread and
        // BluetoothRoutesUpdatedListener can be called before this function
        synchronized (mLock) {
            updateSessionInfosIfNeededLocked();
        }
    }

    private void updateAudioRoutes(AudioRoutesInfo newRoutes) {
        int name = R.string.default_audio_route_name;
        mCurAudioRoutesInfo.mainType = newRoutes.mainType;
        if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0
                || (newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
            name = com.android.internal.R.string.default_audio_route_name_headphones;
        } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
            name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
        } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HDMI) != 0) {
            name = com.android.internal.R.string.default_audio_route_name_hdmi;
        } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_USB) != 0) {
            name = com.android.internal.R.string.default_audio_route_name_usb;
        }

        mDefaultRoute = new MediaRoute2Info.Builder(
                DEFAULT_ROUTE_ID, mContext.getResources().getText(name).toString())
                .setVolumeHandling(mAudioManager.isVolumeFixed()
                        ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                        : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LIVE_VIDEO)
                .build();

        publishRoutes();
    }

    /**
     * Updates the mSessionInfo. Returns true if the session info is changed.
     */
    boolean updateSessionInfosIfNeededLocked() {
        RoutingSessionInfo oldSessionInfo = mSessionInfos.isEmpty() ? null : mSessionInfos.get(0);

        RoutingSessionInfo.Builder builder = new RoutingSessionInfo.Builder(
                SYSTEM_SESSION_ID, "" /* clientPackageName */)
                .setSystemSession(true);
        String activeBtDeviceAddress = mBtRouteProvider.getActiveDeviceAddress();

        if (!TextUtils.isEmpty(activeBtDeviceAddress)) {
            // Bluetooth route. Set the route ID with the device's address.
            builder.addSelectedRoute(activeBtDeviceAddress);
            builder.addTransferrableRoute(mDefaultRoute.getId());
        } else {
            // Default device
            builder.addSelectedRoute(mDefaultRoute.getId());
        }

        for (MediaRoute2Info route : mBluetoothRoutes) {
            if (!TextUtils.equals(activeBtDeviceAddress, route.getId())) {
                builder.addTransferrableRoute(route.getId());
            }
        }

        RoutingSessionInfo newSessionInfo = builder.setProviderId(mUniqueId).build();
        if (Objects.equals(oldSessionInfo, newSessionInfo)) {
            return false;
        } else {
            mSessionInfos.clear();
            mSessionInfos.add(newSessionInfo);
            return true;
        }
    }

    void publishRoutes() {
        MediaRoute2ProviderInfo.Builder builder = new MediaRoute2ProviderInfo.Builder();
        builder.addRoute(mDefaultRoute);
        for (MediaRoute2Info route : mBluetoothRoutes) {
            builder.addRoute(route);
        }
        setAndNotifyProviderState(builder.build());
    }

    void notifySessionInfoUpdated() {
        RoutingSessionInfo sessionInfo;
        synchronized (mLock) {
            sessionInfo = mSessionInfos.get(0);
        }
        mCallback.onSessionUpdated(this, sessionInfo);
    }
}
