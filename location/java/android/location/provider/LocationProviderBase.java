/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.location.provider;

import static android.location.Location.EXTRA_NO_GPS_LOCATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationResult;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

/**
 * Base class for location providers outside the system server.
 *
 * Location providers should be wrapped in a non-exported service which returns the result of
 * {@link #getBinder()} from the service's {@link android.app.Service#onBind(Intent)} method. The
 * service should not be exported so that components other than the system server cannot bind to it.
 * Alternatively, the service may be guarded by a permission that only system server can obtain. The
 * service may specify metadata on its capabilities:
 *
 * <ul>
 *     <li>
 *         "serviceVersion": An integer version code to help tie break if multiple services are
 *         capable of implementing the same location provider. All else equal, the service with the
 *         highest version code will be chosen. Assumed to be 0 if not specified.
 *     </li>
 *     <li>
 *         "serviceIsMultiuser": A boolean property, indicating if the service wishes to take
 *         responsibility for handling changes to the current user on the device. If true, the
 *         service will always be bound from the system user. If false, the service will always be
 *         bound from the current user. If the current user changes, the old binding will be
 *         released, and a new binding established under the new user. Assumed to be false if not
 *         specified.
 *     </li>
 * </ul>
 *
 * <p>The service should have an intent filter in place for the location provider it wishes to
 * implements. Defaults for some providers are specified as constants in this class.
 *
 * @hide
 */
@SystemApi
public abstract class LocationProviderBase {

    /**
     * Callback to be invoked when a flush operation is complete and all flushed locations have been
     * reported.
     */
    public interface OnFlushCompleteCallback {

        /**
         * Should be invoked once the flush is complete.
         */
        void onFlushComplete();
    }

    /**
     * The action the wrapping service should have in its intent filter to implement the
     * {@link android.location.LocationManager#NETWORK_PROVIDER}.
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_NETWORK_PROVIDER =
            "com.android.location.service.v3.NetworkLocationProvider";

    /**
     * The action the wrapping service should have in its intent filter to implement the
     * {@link android.location.LocationManager#FUSED_PROVIDER}.
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_FUSED_PROVIDER =
            "com.android.location.service.FusedLocationProvider";

    private final String mTag;
    private final @Nullable String mPackageName;
    private final @Nullable String mAttributionTag;
    private final IBinder mBinder;

    // write locked on mBinder, read lock is optional depending on atomicity requirements
    private @Nullable volatile ILocationProviderManager mManager;
    private volatile ProviderProperties mProperties;
    private volatile boolean mAllowed;

    public LocationProviderBase(@NonNull Context context, @NonNull String tag,
            @NonNull ProviderProperties properties) {
        mTag = tag;
        mPackageName = context.getPackageName();
        mAttributionTag = context.getAttributionTag();
        mBinder = new Service();

        mManager = null;
        mProperties = Objects.requireNonNull(properties);
        mAllowed = true;
    }

    /**
     * Returns the IBinder instance that should be returned from the
     * {@link android.app.Service#onBind(Intent)} method of the wrapping service.
     */
    public final @Nullable IBinder getBinder() {
        return mBinder;
    }

    /**
     * Sets whether this provider is currently allowed or not. Note that this is specific to the
     * provider only, and is unrelated to global location settings. This is a hint to the location
     * manager that this provider will be unable to fulfill incoming requests. Setting a provider
     * as not allowed will result in the provider being disabled. Setting a provider as allowed
     * means that the provider may be in either the enabled or disabled state.
     *
     * <p>Some guidelines: providers should set their own allowed/disallowed status based only on
     * state "owned" by that provider. For instance, providers should not take into account the
     * state of the location master setting when setting themselves allowed or disallowed, as this
     * state is not owned by a particular provider. If a provider requires some additional user
     * consent that is particular to the provider, this should be use to set the allowed/disallowed
     * state. If the provider proxies to another provider, the child provider's allowed/disallowed
     * state should be taken into account in the parent's allowed state. For most providers, it is
     * expected that they will be always allowed.
     */
    public void setAllowed(boolean allowed) {
        synchronized (mBinder) {
            if (mAllowed == allowed) {
                return;
            }

            mAllowed = allowed;
        }

        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onSetAllowed(mAllowed);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Returns true if this provider is allowed. Providers start as allowed on construction.
     */
    public boolean isAllowed() {
        return mAllowed;
    }

    /**
     * Sets the provider properties that may be queried by clients. Generally speaking, providers
     * should try to avoid changing their properties after construction.
     */
    public void setProperties(@NonNull ProviderProperties properties) {
        synchronized (mBinder) {
            mProperties = Objects.requireNonNull(properties);
        }

        ILocationProviderManager manager = mManager;
        if (manager != null) {
            try {
                manager.onSetProperties(mProperties);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Returns the currently set properties of the provider.
     */
    public @NonNull ProviderProperties getProperties() {
        return mProperties;
    }

    /**
     * Reports a new location from this provider.
     */
    public void reportLocation(@NonNull Location location) {
        reportLocation(LocationResult.create(location));
    }

    /**
     * Reports a new location result from this provider.
     *
     * <p>May only be used from Android S onwards.
     */
    public void reportLocation(@NonNull LocationResult locationResult) {
        ILocationProviderManager manager = mManager;
        if (manager != null) {
            locationResult = locationResult.map(location -> {
                // remove deprecated extras to save on serialization costs
                Bundle extras = location.getExtras();
                if (extras != null && (extras.containsKey(EXTRA_NO_GPS_LOCATION)
                        || extras.containsKey("coarseLocation"))) {
                    location = new Location(location);
                    extras = location.getExtras();
                    extras.remove(EXTRA_NO_GPS_LOCATION);
                    extras.remove("coarseLocation");
                    if (extras.isEmpty()) {
                        location.setExtras(null);
                    }
                }
                return location;
            });

            try {
                manager.onReportLocation(locationResult);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (RuntimeException e) {
                Log.w(mTag, e);
            }
        }
    }

    /**
     * Set the current {@link ProviderRequest} for this provider. Each call to this method overrides
     * any prior ProviderRequests. The provider should immediately attempt to provide locations (or
     * not provide locations) according to the parameters of the provider request.
     */
    public abstract void onSetRequest(@NonNull ProviderRequest request);

    /**
     * Requests a flush of any pending batched locations. The callback must always be invoked once
     * per invocation, and should be invoked after {@link #reportLocation(LocationResult)} has been
     * invoked with any flushed locations. The callback may be invoked immediately if no locations
     * are flushed.
     */
    public abstract void onFlush(@NonNull OnFlushCompleteCallback callback);

    /**
     * Implements optional custom commands.
     */
    public abstract void onSendExtraCommand(@NonNull String command,
            @SuppressLint("NullableCollection")
            @Nullable Bundle extras);

    private final class Service extends ILocationProvider.Stub {

        Service() {}

        @Override
        public void setLocationProviderManager(ILocationProviderManager manager) {
            synchronized (mBinder) {
                try {
                    manager.onInitialize(mAllowed, mProperties, mPackageName, mAttributionTag);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } catch (RuntimeException e) {
                    Log.w(mTag, e);
                }

                mManager = manager;
            }
        }

        @Override
        public void setRequest(ProviderRequest request) {
            onSetRequest(request);
        }

        @Override
        public void flush() {
            onFlush(this::onFlushComplete);
        }

        private void onFlushComplete() {
            ILocationProviderManager manager = mManager;
            if (manager != null) {
                try {
                    manager.onFlushComplete();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } catch (RuntimeException e) {
                    Log.w(mTag, e);
                }
            }
        }

        @Override
        public void sendExtraCommand(String command, Bundle extras) {
            onSendExtraCommand(command, extras);
        }
    }
}
