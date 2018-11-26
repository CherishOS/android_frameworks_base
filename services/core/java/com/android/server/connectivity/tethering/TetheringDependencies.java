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
 * limitations under the License.
 */

package com.android.server.connectivity.tethering;

import android.content.Context;
import android.net.NetworkRequest;
import android.net.ip.IpServer;
import android.net.util.SharedLog;
import android.os.Handler;

import com.android.internal.util.StateMachine;
import com.android.server.connectivity.MockableSystemProperties;

import java.util.ArrayList;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public class TetheringDependencies {
    public OffloadHardwareInterface getOffloadHardwareInterface(Handler h, SharedLog log) {
        return new OffloadHardwareInterface(h, log);
    }

    public UpstreamNetworkMonitor getUpstreamNetworkMonitor(Context ctx, StateMachine target,
            SharedLog log, int what) {
        return new UpstreamNetworkMonitor(ctx, target, log, what);
    }

    public IPv6TetheringCoordinator getIPv6TetheringCoordinator(
            ArrayList<IpServer> notifyList, SharedLog log) {
        return new IPv6TetheringCoordinator(notifyList, log);
    }

    public IpServer.Dependencies getIpServerDependencies() {
        return new IpServer.Dependencies();
    }

    public boolean isTetheringSupported() {
        return true;
    }

    public NetworkRequest getDefaultNetworkRequest() {
        return null;
    }

    public EntitlementManager getEntitlementManager(Context ctx, SharedLog log,
            MockableSystemProperties systemProperties) {
        return new EntitlementManager(ctx, log, systemProperties);
    }
}
