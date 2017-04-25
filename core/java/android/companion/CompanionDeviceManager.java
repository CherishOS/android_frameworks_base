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

package android.companion;


import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * System level service for managing companion devices
 *
 * <p>To obtain an instance call {@link Context#getSystemService}({@link
 * Context#COMPANION_DEVICE_SERVICE}) Then, call {@link #associate(AssociationRequest,
 * Callback, Handler)} to initiate the flow of associating current package with a
 * device selected by user.</p>
 *
 * @see AssociationRequest
 */
public final class CompanionDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "CompanionDeviceManager";

    /**
     * A device, returned in the activity result of the {@link IntentSender} received in
     * {@link Callback#onDeviceFound}
     */
    public static final String EXTRA_DEVICE = "android.companion.extra.DEVICE";

    /**
     * The package name of the companion device discovery component.
     *
     * @hide
     */
    public static final String COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME =
            "com.android.companiondevicemanager";

    /**
     * A callback to receive once at least one suitable device is found, or the search failed
     * (e.g. timed out)
     */
    public abstract static class Callback {

        /**
         * Called once at least one suitable device is found
         *
         * @param chooserLauncher a {@link IntentSender} to launch the UI for user to select a
         *                        device
         */
        public abstract void onDeviceFound(IntentSender chooserLauncher);

        /**
         * Called if there was an error looking for device(s), e.g. timeout
         *
         * @param error the cause of the error
         */
        public abstract void onFailure(CharSequence error);
    }

    private final ICompanionDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public CompanionDeviceManager(
            @Nullable ICompanionDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Associate this app with a companion device, selected by user
     *
     * <p>Once at least one appropriate device is found, {@code callback} will be called with a
     * {@link PendingIntent} that can be used to show the list of available devices for the user
     * to select.
     * It should be started for result (i.e. using
     * {@link android.app.Activity#startIntentSenderForResult}), as the resulting
     * {@link android.content.Intent} will contain extra {@link #EXTRA_DEVICE}, with the selected
     * device. (e.g. {@link android.bluetooth.BluetoothDevice})</p>
     *
     * <p>If your app needs to be excluded from battery optimizations (run in the background)
     * or to have unrestricted data access (use data in the background) you can declare that
     * you use the {@link android.Manifest.permission#RUN_IN_BACKGROUND} and {@link
     * android.Manifest.permission#USE_DATA_IN_BACKGROUND} respectively. Note that these
     * special capabilities have a negative effect on the device's battery and user's data
     * usage, therefore you should requested them when absolutely necessary.</p>
     *
     * <p>You can call {@link #getAssociations} to get the list of currently associated
     * devices, and {@link #disassociate} to remove an association. Consider doing so when the
     * association is no longer relevant to avoid unnecessary battery and/or data drain resulting
     * from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param request specific details about this request
     * @param callback will be called once there's at least one device found for user to choose from
     * @param handler A handler to control which thread the callback will be delivered on, or null,
     *                to deliver it on main thread
     *
     * @see AssociationRequest
     */
    public void associate(
            @NonNull AssociationRequest request,
            @NonNull Callback callback,
            @Nullable Handler handler) {
        if (!checkFeaturePresent()) {
            return;
        }
        checkNotNull(request, "Request cannot be null");
        checkNotNull(callback, "Callback cannot be null");
        final Handler finalHandler = Handler.mainIfNull(handler);
        try {
            mService.associate(
                    request,
                    //TODO implicit pointer to outer class -> =null onDestroy
                    //TODO onStop if isFinishing -> stopScan
                    new IFindDeviceCallback.Stub() {
                        @Override
                        public void onSuccess(PendingIntent launcher) {
                            finalHandler.post(() -> {
                                callback.onDeviceFound(launcher.getIntentSender());
                            });
                        }

                        @Override
                        public void onFailure(CharSequence reason) {
                            finalHandler.post(() -> callback.onFailure(reason));
                        }
                    },
                    mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @return a list of MAC addresses of devices that have been previously associated with the
     * current app. You can use these with {@link #disassociate}
     */
    @NonNull
    public List<String> getAssociations() {
        if (!checkFeaturePresent()) {
            return Collections.emptyList();
        }
        try {
            return mService.getAssociations(mContext.getPackageName(), mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the association between this app and the device with the given mac address.
     *
     * <p>Any privileges provided via being associated with a given device will be revoked</p>
     *
     * <p>Consider doing so when the
     * association is no longer relevant to avoid unnecessary battery and/or data drain resulting
     * from special privileges that the association provides</p>
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param deviceMacAddress the MAC address of device to disassociate from this app
     */
    public void disassociate(@NonNull String deviceMacAddress) {
        if (!checkFeaturePresent()) {
            return;
        }
        try {
            mService.disassociate(deviceMacAddress, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request notification access for the given component.
     *
     * The given component must follow the protocol specified in {@link NotificationListenerService}
     *
     * Only components from the same {@link ComponentName#getPackageName package} as the calling app
     * are allowed.
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     */
    public void requestNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return;
        }
        try {
            mService.requestNotificationAccess(component).send();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (PendingIntent.CanceledException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check whether the given component can access the notifications via a
     * {@link NotificationListenerService}
     *
     * Your app must have an association with a device before calling this API
     *
     * <p>Calling this API requires a uses-feature
     * {@link PackageManager#FEATURE_COMPANION_DEVICE_SETUP} declaration in the manifest</p>
     *
     * @param component the name of the component
     * @return whether the given component has the notification listener permission
     */
    public boolean hasNotificationAccess(ComponentName component) {
        if (!checkFeaturePresent()) {
            return false;
        }
        try {
            return mService.hasNotificationAccess(component);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean checkFeaturePresent() {
        boolean featurePresent = mService != null;
        if (!featurePresent && DEBUG) {
            Log.d(LOG_TAG, "Feature " + PackageManager.FEATURE_COMPANION_DEVICE_SETUP
                    + " not available");
        }
        return featurePresent;
    }
}
