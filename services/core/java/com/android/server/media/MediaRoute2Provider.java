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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

abstract class MediaRoute2Provider {
    final ComponentName mComponentName;
    final String mUniqueId;
    final Object mLock = new Object();

    Callback mCallback;
    boolean mIsSystemRouteProvider;
    private volatile MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mLock")
    final List<RoutingSessionInfo> mSessionInfos = new ArrayList<>();

    MediaRoute2Provider(@NonNull ComponentName componentName) {
        mComponentName = Objects.requireNonNull(componentName, "Component name must not be null.");
        mUniqueId = componentName.flattenToShortString();
    }

    public void setCallback(MediaRoute2ProviderProxy.Callback callback) {
        mCallback = callback;
    }

    public abstract void requestCreateSession(String packageName, String routeId, long requestId,
            @Nullable Bundle sessionHints);
    public abstract void releaseSession(String sessionId);
    public abstract void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference);

    public abstract void selectRoute(String sessionId, String routeId);
    public abstract void deselectRoute(String sessionId, String routeId);
    public abstract void transferToRoute(String sessionId, String routeId);

    public abstract void sendControlRequest(String routeId, Intent request);
    public abstract void requestSetVolume(String routeId, int volume);
    public abstract void requestUpdateVolume(String routeId, int delta);

    @NonNull
    public String getUniqueId() {
        return mUniqueId;
    }

    @Nullable
    public MediaRoute2ProviderInfo getProviderInfo() {
        return mProviderInfo;
    }

    @NonNull
    public List<RoutingSessionInfo> getSessionInfos() {
        synchronized (mLock) {
            return mSessionInfos;
        }
    }

    void setProviderState(MediaRoute2ProviderInfo providerInfo) {
        if (providerInfo == null) {
            mProviderInfo = null;
        } else {
            mProviderInfo = new MediaRoute2ProviderInfo.Builder(providerInfo)
                    .setUniqueId(mUniqueId)
                    .setSystemRouteProvider(mIsSystemRouteProvider)
                    .build();
        }
    }

    void notifyProviderState() {
        if (mCallback != null) {
            mCallback.onProviderStateChanged(this);
        }
    }

    void setAndNotifyProviderState(MediaRoute2ProviderInfo providerInfo) {
        setProviderState(providerInfo);
        notifyProviderState();
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public interface Callback {
        void onProviderStateChanged(@Nullable MediaRoute2Provider provider);
        void onSessionCreated(@NonNull MediaRoute2Provider provider,
                @Nullable RoutingSessionInfo sessionInfo, long requestId);
        void onSessionCreationFailed(@NonNull MediaRoute2Provider provider, long requestId);
        void onSessionUpdated(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
        void onSessionReleased(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
    }
}
