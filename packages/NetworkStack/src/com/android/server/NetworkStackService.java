/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.net.dhcp.IDhcpServer.STATUS_INVALID_ARGUMENT;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.dhcp.IDhcpServer.STATUS_UNKNOWN_ERROR;

import static com.android.server.util.PermissionUtil.checkDumpPermission;
import static com.android.server.util.PermissionUtil.checkNetworkStackCallingPermission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.IIpMemoryStore;
import android.net.IIpMemoryStoreCallbacks;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkStackConnector;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.PrivateDnsConfigParcel;
import android.net.dhcp.DhcpServer;
import android.net.dhcp.DhcpServingParams;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.net.ip.IpClient;
import android.net.shared.PrivateDnsConfig;
import android.net.util.SharedLog;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.connectivity.ipmemorystore.IpMemoryStoreService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Android service used to start the network stack when bound to via an intent.
 *
 * <p>The service returns a binder for the system server to communicate with the network stack.
 */
public class NetworkStackService extends Service {
    private static final String TAG = NetworkStackService.class.getSimpleName();
    private static NetworkStackConnector sConnector;

    /**
     * Create a binder connector for the system server to communicate with the network stack.
     *
     * <p>On platforms where the network stack runs in the system server process, this method may
     * be called directly instead of obtaining the connector by binding to the service.
     */
    public static synchronized IBinder makeConnector(Context context) {
        if (sConnector == null) {
            sConnector = new NetworkStackConnector(context);
        }
        return sConnector;
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        return makeConnector(this);
    }

    /**
     * An interface for internal clients of the network stack service that can return
     * or create inline instances of the service it manages.
     */
    public interface NetworkStackServiceManager {
        /**
         * Get an instance of the IpMemoryStoreService.
         */
        IIpMemoryStore getIpMemoryStoreService();
    }

    private static class NetworkStackConnector extends INetworkStackConnector.Stub
            implements NetworkStackServiceManager {
        private static final int NUM_VALIDATION_LOG_LINES = 20;
        private final Context mContext;
        private final INetd mNetd;
        private final NetworkObserverRegistry mObserverRegistry;
        private final ConnectivityManager mCm;
        @GuardedBy("mIpClients")
        private final ArrayList<WeakReference<IpClient>> mIpClients = new ArrayList<>();
        private final IpMemoryStoreService mIpMemoryStoreService;

        private static final int MAX_VALIDATION_LOGS = 10;
        @GuardedBy("mValidationLogs")
        private final ArrayDeque<SharedLog> mValidationLogs = new ArrayDeque<>(MAX_VALIDATION_LOGS);

        private static final String DUMPSYS_ARG_VERSION = "version";

        /** Version of the framework AIDL interfaces observed. Should hold only one value. */
        @GuardedBy("mFrameworkAidlVersions")
        private final ArraySet<Integer> mFrameworkAidlVersions = new ArraySet<>(1);
        private final int mNetdAidlVersion;

        private SharedLog addValidationLogs(Network network, String name) {
            final SharedLog log = new SharedLog(NUM_VALIDATION_LOG_LINES, network + " - " + name);
            synchronized (mValidationLogs) {
                while (mValidationLogs.size() >= MAX_VALIDATION_LOGS) {
                    mValidationLogs.removeLast();
                }
                mValidationLogs.addFirst(log);
            }
            return log;
        }

        NetworkStackConnector(Context context) {
            mContext = context;
            mNetd = INetd.Stub.asInterface(
                    (IBinder) context.getSystemService(Context.NETD_SERVICE));
            mObserverRegistry = new NetworkObserverRegistry();
            mCm = context.getSystemService(ConnectivityManager.class);
            mIpMemoryStoreService = new IpMemoryStoreService(context);

            int netdVersion;
            try {
                netdVersion = mNetd.getInterfaceVersion();
            } catch (RemoteException e) {
                mLog.e("Error obtaining INetd version", e);
                netdVersion = -1;
            }
            mNetdAidlVersion = netdVersion;

            try {
                mObserverRegistry.register(mNetd);
            } catch (RemoteException e) {
                mLog.e("Error registering observer on Netd", e);
            }
        }

        private void updateSystemAidlVersion(final int version) {
            synchronized (mFrameworkAidlVersions) {
                mFrameworkAidlVersions.add(version);
            }
        }

        @NonNull
        private final SharedLog mLog = new SharedLog(TAG);

        @Override
        public void makeDhcpServer(@NonNull String ifName, @NonNull DhcpServingParamsParcel params,
                @NonNull IDhcpServerCallbacks cb) throws RemoteException {
            checkNetworkStackCallingPermission();
            updateSystemAidlVersion(cb.getInterfaceVersion());
            final DhcpServer server;
            try {
                server = new DhcpServer(
                        ifName,
                        DhcpServingParams.fromParcelableObject(params),
                        mLog.forSubComponent(ifName + ".DHCP"));
            } catch (DhcpServingParams.InvalidParameterException e) {
                mLog.e("Invalid DhcpServingParams", e);
                cb.onDhcpServerCreated(STATUS_INVALID_ARGUMENT, null);
                return;
            } catch (Exception e) {
                mLog.e("Unknown error starting DhcpServer", e);
                cb.onDhcpServerCreated(STATUS_UNKNOWN_ERROR, null);
                return;
            }
            cb.onDhcpServerCreated(STATUS_SUCCESS, server);
        }

        @Override
        public void makeNetworkMonitor(Network network, String name, INetworkMonitorCallbacks cb)
                throws RemoteException {
            updateSystemAidlVersion(cb.getInterfaceVersion());
            final SharedLog log = addValidationLogs(network, name);
            final NetworkMonitor nm = new NetworkMonitor(mContext, cb, network, log);
            cb.onNetworkMonitorCreated(new NetworkMonitorImpl(nm));
        }

        @Override
        public void makeIpClient(String ifName, IIpClientCallbacks cb) throws RemoteException {
            updateSystemAidlVersion(cb.getInterfaceVersion());
            final IpClient ipClient = new IpClient(mContext, ifName, cb, mObserverRegistry, this);

            synchronized (mIpClients) {
                final Iterator<WeakReference<IpClient>> it = mIpClients.iterator();
                while (it.hasNext()) {
                    final IpClient ipc = it.next().get();
                    if (ipc == null) {
                        it.remove();
                    }
                }
                mIpClients.add(new WeakReference<>(ipClient));
            }

            cb.onIpClientCreated(ipClient.makeConnector());
        }

        @Override
        public IIpMemoryStore getIpMemoryStoreService() {
            return mIpMemoryStoreService;
        }

        @Override
        public void fetchIpMemoryStore(@NonNull final IIpMemoryStoreCallbacks cb)
                throws RemoteException {
            updateSystemAidlVersion(cb.getInterfaceVersion());
            cb.onIpMemoryStoreFetched(mIpMemoryStoreService);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            checkDumpPermission();

            final IndentingPrintWriter pw = new IndentingPrintWriter(fout, "  ");
            pw.println("NetworkStack version:");
            dumpVersion(pw);
            pw.println();

            if (args != null && args.length >= 1 && DUMPSYS_ARG_VERSION.equals(args[0])) {
                return;
            }

            pw.println("NetworkStack logs:");
            mLog.dump(fd, pw, args);

            // Dump full IpClient logs for non-GCed clients
            pw.println();
            pw.println("Recently active IpClient logs:");
            final ArrayList<IpClient> ipClients = new ArrayList<>();
            final HashSet<String> dumpedIpClientIfaces = new HashSet<>();
            synchronized (mIpClients) {
                for (WeakReference<IpClient> ipcRef : mIpClients) {
                    final IpClient ipc = ipcRef.get();
                    if (ipc != null) {
                        ipClients.add(ipc);
                    }
                }
            }

            for (IpClient ipc : ipClients) {
                pw.println(ipc.getName());
                pw.increaseIndent();
                ipc.dump(fd, pw, args);
                pw.decreaseIndent();
                dumpedIpClientIfaces.add(ipc.getInterfaceName());
            }

            // State machine and connectivity metrics logs are kept for GCed IpClients
            pw.println();
            pw.println("Other IpClient logs:");
            IpClient.dumpAllLogs(fout, dumpedIpClientIfaces);

            pw.println();
            pw.println("Validation logs (most recent first):");
            synchronized (mValidationLogs) {
                for (SharedLog p : mValidationLogs) {
                    pw.println(p.getTag());
                    pw.increaseIndent();
                    p.dump(fd, pw, args);
                    pw.decreaseIndent();
                }
            }
        }

        /**
         * Dump version information of the module and detected system version.
         */
        private void dumpVersion(@NonNull PrintWriter fout) {
            fout.println("NetworkStackConnector: " + this.VERSION);
            synchronized (mFrameworkAidlVersions) {
                fout.println("SystemServer: " + mFrameworkAidlVersions);
            }
            fout.println("Netd: " + mNetdAidlVersion);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }
    }

    private static class NetworkMonitorImpl extends INetworkMonitor.Stub {
        private final NetworkMonitor mNm;

        NetworkMonitorImpl(NetworkMonitor nm) {
            mNm = nm;
        }

        @Override
        public void start() {
            checkNetworkStackCallingPermission();
            mNm.start();
        }

        @Override
        public void launchCaptivePortalApp() {
            checkNetworkStackCallingPermission();
            mNm.launchCaptivePortalApp();
        }

        @Override
        public void notifyCaptivePortalAppFinished(int response) {
            checkNetworkStackCallingPermission();
            mNm.notifyCaptivePortalAppFinished(response);
        }

        @Override
        public void setAcceptPartialConnectivity() {
            checkNetworkStackCallingPermission();
            mNm.setAcceptPartialConnectivity();
        }

        @Override
        public void forceReevaluation(int uid) {
            checkNetworkStackCallingPermission();
            mNm.forceReevaluation(uid);
        }

        @Override
        public void notifyPrivateDnsChanged(PrivateDnsConfigParcel config) {
            checkNetworkStackCallingPermission();
            mNm.notifyPrivateDnsSettingsChanged(PrivateDnsConfig.fromParcel(config));
        }

        @Override
        public void notifyDnsResponse(int returnCode) {
            checkNetworkStackCallingPermission();
            mNm.notifyDnsResponse(returnCode);
        }

        @Override
        public void notifyNetworkConnected(LinkProperties lp, NetworkCapabilities nc) {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkConnected(lp, nc);
        }

        @Override
        public void notifyNetworkDisconnected() {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkDisconnected();
        }

        @Override
        public void notifyLinkPropertiesChanged(LinkProperties lp) {
            checkNetworkStackCallingPermission();
            mNm.notifyLinkPropertiesChanged(lp);
        }

        @Override
        public void notifyNetworkCapabilitiesChanged(NetworkCapabilities nc) {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkCapabilitiesChanged(nc);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }
    }
}
