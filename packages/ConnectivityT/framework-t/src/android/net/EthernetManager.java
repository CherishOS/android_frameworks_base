/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * A class that manages and configures Ethernet interfaces.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.ETHERNET_SERVICE)
public class EthernetManager {
    private static final String TAG = "EthernetManager";

    private final IEthernetManager mService;
    @GuardedBy("mListeners")
    private final ArrayList<ListenerInfo> mListeners = new ArrayList<>();
    private final IEthernetServiceListener.Stub mServiceListener =
            new IEthernetServiceListener.Stub() {
                @Override
                public void onInterfaceStateChanged(String iface, int state, int role,
                        IpConfiguration configuration) {
                    synchronized (mListeners) {
                        for (ListenerInfo li : mListeners) {
                            li.executor.execute(() ->
                                    li.listener.onInterfaceStateChanged(iface, state, role,
                                            configuration));
                        }
                    }
                }
            };

    private static class ListenerInfo {
        @NonNull
        public final Executor executor;
        @NonNull
        public final InterfaceStateListener listener;

        private ListenerInfo(@NonNull Executor executor, @NonNull InterfaceStateListener listener) {
            this.executor = executor;
            this.listener = listener;
        }
    }

    /**
     * The interface is absent.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int STATE_ABSENT = 0;

    /**
     * The interface is present but link is down.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int STATE_LINK_DOWN = 1;

    /**
     * The interface is present and link is up.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int STATE_LINK_UP = 2;

    /** @hide */
    @IntDef(prefix = "STATE_", value = {STATE_ABSENT, STATE_LINK_DOWN, STATE_LINK_UP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InterfaceState {}

    /**
     * The interface currently does not have any specific role.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int ROLE_NONE = 0;

    /**
     * The interface is in client mode (e.g., connected to the Internet).
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int ROLE_CLIENT = 1;

    /**
     * Ethernet interface is in server mode (e.g., providing Internet access to tethered devices).
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int ROLE_SERVER = 2;

    /** @hide */
    @IntDef(prefix = "ROLE_", value = {ROLE_NONE, ROLE_CLIENT, ROLE_SERVER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Role {}

    /**
     * A listener that receives notifications about the state of Ethernet interfaces on the system.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public interface InterfaceStateListener {
        /**
         * Called when an Ethernet interface changes state.
         *
         * @param iface the name of the interface.
         * @param state the current state of the interface, or {@link #STATE_ABSENT} if the
         *              interface was removed.
         * @param role whether the interface is in the client mode or server mode.
         * @param configuration the current IP configuration of the interface.
         * @hide
         */
        @SystemApi(client = MODULE_LIBRARIES)
        void onInterfaceStateChanged(@NonNull String iface, @InterfaceState int state,
                @Role int role, @Nullable IpConfiguration configuration);
    }

    /**
     * A listener interface to receive notification on changes in Ethernet.
     * This has never been a supported API. Use {@link InterfaceStateListener} instead.
     * @hide
     */
    public interface Listener extends InterfaceStateListener {
        /**
         * Called when Ethernet port's availability is changed.
         * @param iface Ethernet interface name
         * @param isAvailable {@code true} if Ethernet port exists.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        void onAvailabilityChanged(String iface, boolean isAvailable);

        /** Default implementation for backwards compatibility. Only calls the legacy listener. */
        default void onInterfaceStateChanged(@NonNull String iface, @InterfaceState int state,
                @Role int role, @Nullable IpConfiguration configuration) {
            onAvailabilityChanged(iface, (state >= STATE_LINK_UP));
        }

    }

    /**
     * Create a new EthernetManager instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#ETHERNET_SERVICE Context.ETHERNET_SERVICE}.
     * @hide
     */
    public EthernetManager(Context context, IEthernetManager service) {
        mService = service;
    }

    /**
     * Get Ethernet configuration.
     * @return the Ethernet Configuration, contained in {@link IpConfiguration}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public IpConfiguration getConfiguration(String iface) {
        try {
            return mService.getConfiguration(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set Ethernet configuration.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setConfiguration(@NonNull String iface, @NonNull IpConfiguration config) {
        try {
            mService.setConfiguration(iface, config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates whether the system currently has one or more Ethernet interfaces.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isAvailable() {
        return getAvailableInterfaces().length > 0;
    }

    /**
     * Indicates whether the system has given interface.
     *
     * @param iface Ethernet interface name
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isAvailable(String iface) {
        try {
            return mService.isAvailable(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a listener.
     * This has never been a supported API. Use {@link #addInterfaceStateListener} instead.
     *
     * @param listener A {@link Listener} to add.
     * @throws IllegalArgumentException If the listener is null.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void addListener(@NonNull Listener listener) {
        addListener(listener, BackgroundThread.getExecutor());
    }

    /**
     * Adds a listener.
     * This has never been a supported API. Use {@link #addInterfaceStateListener} instead.
     *
     * @param listener A {@link Listener} to add.
     * @param executor Executor to run callbacks on.
     * @throws IllegalArgumentException If the listener or executor is null.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void addListener(@NonNull Listener listener, @NonNull Executor executor) {
        addInterfaceStateListener(executor, listener);
    }

    /**
     * Listen to changes in the state of Ethernet interfaces.
     *
     * Adds a listener to receive notification for any state change of all existing Ethernet
     * interfaces.
     * <p>{@link Listener#onInterfaceStateChanged} will be triggered immediately for all
     * existing interfaces upon adding a listener. The same method will be called on the
     * listener every time any of the interface changes state. In particular, if an
     * interface is removed, it will be called with state {@link #STATE_ABSENT}.
     * <p>Use {@link #removeInterfaceStateListener} with the same object to stop listening.
     *
     * @param executor Executor to run callbacks on.
     * @param listener A {@link Listener} to add.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    @SystemApi(client = MODULE_LIBRARIES)
    public void addInterfaceStateListener(@NonNull Executor executor,
            @NonNull InterfaceStateListener listener) {
        if (listener == null || executor == null) {
            throw new NullPointerException("listener and executor must not be null");
        }
        synchronized (mListeners) {
            mListeners.add(new ListenerInfo(executor, listener));
            if (mListeners.size() == 1) {
                try {
                    mService.addListener(mServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Returns an array of available Ethernet interface names.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String[] getAvailableInterfaces() {
        try {
            return mService.getAvailableInterfaces();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Removes a listener.
     *
     * @param listener A {@link Listener} to remove.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public void removeInterfaceStateListener(@NonNull InterfaceStateListener listener) {
        Objects.requireNonNull(listener);
        synchronized (mListeners) {
            mListeners.removeIf(l -> l.listener == listener);
            if (mListeners.isEmpty()) {
                try {
                    mService.removeListener(mServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Removes a listener.
     * This has never been a supported API. Use {@link #removeInterfaceStateListener} instead.
     * @param listener A {@link Listener} to remove.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void removeListener(@NonNull Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        removeInterfaceStateListener(listener);
    }

    /**
     * Whether to treat interfaces created by {@link TestNetworkManager#createTapInterface}
     * as Ethernet interfaces. The effects of this method apply to any test interfaces that are
     * already present on the system.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public void setIncludeTestInterfaces(boolean include) {
        try {
            mService.setIncludeTestInterfaces(include);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A request for a tethered interface.
     */
    public static class TetheredInterfaceRequest {
        private final IEthernetManager mService;
        private final ITetheredInterfaceCallback mCb;

        private TetheredInterfaceRequest(@NonNull IEthernetManager service,
                @NonNull ITetheredInterfaceCallback cb) {
            this.mService = service;
            this.mCb = cb;
        }

        /**
         * Release the request, causing the interface to revert back from tethering mode if there
         * is no other requestor.
         */
        public void release() {
            try {
                mService.releaseTetheredInterface(mCb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callback for {@link #requestTetheredInterface(TetheredInterfaceCallback)}.
     */
    public interface TetheredInterfaceCallback {
        /**
         * Called when the tethered interface is available.
         * @param iface The name of the interface.
         */
        void onAvailable(@NonNull String iface);

        /**
         * Called when the tethered interface is now unavailable.
         */
        void onUnavailable();
    }

    /**
     * Request a tethered interface in tethering mode.
     *
     * <p>When this method is called and there is at least one ethernet interface available, the
     * system will designate one to act as a tethered interface. If there is already a tethered
     * interface, the existing interface will be used.
     * @param callback A callback to be called once the request has been fulfilled.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_STACK,
            android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK
    })
    @NonNull
    public TetheredInterfaceRequest requestTetheredInterface(@NonNull final Executor executor,
            @NonNull final TetheredInterfaceCallback callback) {
        Objects.requireNonNull(callback, "Callback must be non-null");
        Objects.requireNonNull(executor, "Executor must be non-null");
        final ITetheredInterfaceCallback cbInternal = new ITetheredInterfaceCallback.Stub() {
            @Override
            public void onAvailable(String iface) {
                executor.execute(() -> callback.onAvailable(iface));
            }

            @Override
            public void onUnavailable() {
                executor.execute(() -> callback.onUnavailable());
            }
        };

        try {
            mService.requestTetheredInterface(cbInternal);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new TetheredInterfaceRequest(mService, cbInternal);
    }

    private static final class InternalNetworkManagementListener
            extends IEthernetNetworkManagementListener.Stub {
        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final BiConsumer<Network, EthernetNetworkManagementException> mListener;

        InternalNetworkManagementListener(
                @NonNull final Executor executor,
                @NonNull final BiConsumer<Network, EthernetNetworkManagementException> listener) {
            Objects.requireNonNull(executor, "Pass a non-null executor");
            Objects.requireNonNull(listener, "Pass a non-null listener");
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onComplete(
                @Nullable final Network network,
                @Nullable final EthernetNetworkManagementException e) {
            mExecutor.execute(() -> mListener.accept(network, e));
        }
    }

    private InternalNetworkManagementListener getInternalNetworkManagementListener(
            @Nullable final Executor executor,
            @Nullable final BiConsumer<Network, EthernetNetworkManagementException> listener) {
        if (null != listener) {
            Objects.requireNonNull(executor, "Pass a non-null executor, or a null listener");
        }
        final InternalNetworkManagementListener proxy;
        if (null == listener) {
            proxy = null;
        } else {
            proxy = new InternalNetworkManagementListener(executor, listener);
        }
        return proxy;
    }

    /**
     * Updates the configuration of an automotive device's ethernet network.
     *
     * The {@link EthernetNetworkUpdateRequest} {@code request} argument describes how to update the
     * configuration for this network.
     * Use {@link StaticIpConfiguration.Builder} to build a {@code StaticIpConfiguration} object for
     * this network to put inside the {@code request}.
     * Similarly, use {@link NetworkCapabilities.Builder} to build a {@code NetworkCapabilities}
     * object for this network to put inside the {@code request}.
     *
     * If non-null, the listener will be called exactly once after this is called, unless
     * a synchronous exception was thrown.
     *
     * @param iface the name of the interface to act upon.
     * @param request the {@link EthernetNetworkUpdateRequest} used to set an ethernet network's
     *                {@link StaticIpConfiguration} and {@link NetworkCapabilities} values.
     * @param executor an {@link Executor} to execute the listener on. Optional if listener is null.
     * @param listener an optional {@link BiConsumer} to listen for completion of the operation.
     * @throws SecurityException if the process doesn't hold
     *                          {@link android.Manifest.permission.MANAGE_ETHERNET_NETWORKS}.
     * @throws UnsupportedOperationException if called on a non-automotive device or on an
     *                                       unsupported interface.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.MANAGE_ETHERNET_NETWORKS})
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    public void updateConfiguration(
            @NonNull String iface,
            @NonNull EthernetNetworkUpdateRequest request,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable BiConsumer<Network, EthernetNetworkManagementException> listener) {
        Objects.requireNonNull(iface, "iface must be non-null");
        Objects.requireNonNull(request, "request must be non-null");
        final InternalNetworkManagementListener proxy = getInternalNetworkManagementListener(
                executor, listener);
        try {
            mService.updateConfiguration(iface, request, proxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set an ethernet network's link state up.
     *
     * When the link is successfully turned up, the listener will be called with the resulting
     * network. If any error or unexpected condition happens while the system tries to turn the
     * interface up, the listener will be called with an appropriate exception.
     * The listener is guaranteed to be called exactly once for each call to this method, but this
     * may take an unbounded amount of time depending on the actual network conditions.
     *
     * @param iface the name of the interface to act upon.
     * @param executor an {@link Executor} to execute the listener on. Optional if listener is null.
     * @param listener an optional {@link BiConsumer} to listen for completion of the operation.
     * @throws SecurityException if the process doesn't hold
     *                          {@link android.Manifest.permission.MANAGE_ETHERNET_NETWORKS}.
     * @throws UnsupportedOperationException if called on a non-automotive device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.MANAGE_ETHERNET_NETWORKS})
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    public void connectNetwork(
            @NonNull String iface,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable BiConsumer<Network, EthernetNetworkManagementException> listener) {
        Objects.requireNonNull(iface, "iface must be non-null");
        final InternalNetworkManagementListener proxy = getInternalNetworkManagementListener(
                executor, listener);
        try {
            mService.connectNetwork(iface, proxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set an ethernet network's link state down.
     *
     * When the link is successfully turned down, the listener will be called with the network that
     * was torn down, if any. If any error or unexpected condition happens while the system tries to
     * turn the interface down, the listener will be called with an appropriate exception.
     * The listener is guaranteed to be called exactly once for each call to this method.
     *
     * @param iface the name of the interface to act upon.
     * @param executor an {@link Executor} to execute the listener on. Optional if listener is null.
     * @param listener an optional {@link BiConsumer} to listen for completion of the operation.
     * @throws SecurityException if the process doesn't hold
     *                          {@link android.Manifest.permission.MANAGE_ETHERNET_NETWORKS}.
     * @throws UnsupportedOperationException if called on a non-automotive device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.MANAGE_ETHERNET_NETWORKS})
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    public void disconnectNetwork(
            @NonNull String iface,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable BiConsumer<Network, EthernetNetworkManagementException> listener) {
        Objects.requireNonNull(iface, "iface must be non-null");
        final InternalNetworkManagementListener proxy = getInternalNetworkManagementListener(
                executor, listener);
        try {
            mService.disconnectNetwork(iface, proxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
