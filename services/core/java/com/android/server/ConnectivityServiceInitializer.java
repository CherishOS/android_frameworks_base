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

package com.android.server;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;

import android.content.Context;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Connectivity service initializer for core networking. This is called by system server to create
 * a new instance of ConnectivityService.
 */
public final class ConnectivityServiceInitializer extends SystemService {
    private static final String TAG = ConnectivityServiceInitializer.class.getSimpleName();
    private final ConnectivityService mConnectivity;

    public ConnectivityServiceInitializer(Context context) {
        super(context);
        // Load JNI libraries used by ConnectivityService and its dependencies
        System.loadLibrary("service-connectivity");
        // TODO: Define formal APIs to get the needed services.
        mConnectivity = new ConnectivityService(context, getNetworkManagementService(),
                getNetworkStatsService(), getNetworkPolicyManager());
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.CONNECTIVITY_SERVICE);
        publishBinderService(Context.CONNECTIVITY_SERVICE, mConnectivity,
                /* allowIsolated= */ false, DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
    }

    private INetworkManagementService getNetworkManagementService() {
        return INetworkManagementService.Stub.asInterface(
               ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
    }

    private INetworkStatsService getNetworkStatsService() {
        return INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
    }

    private INetworkPolicyManager getNetworkPolicyManager() {
        return INetworkPolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
    }

}
