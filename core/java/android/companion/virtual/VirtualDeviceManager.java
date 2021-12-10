/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.companion.AssociationInfo;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualTouchscreen;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * System level service for managing virtual devices.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManager";

    private final IVirtualDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public VirtualDeviceManager(
            @Nullable IVirtualDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Creates a virtual device.
     *
     * @param associationId The association ID as returned by {@link AssociationInfo#getId()} from
     *   Companion Device Manager. Virtual devices must have a corresponding association with CDM in
     *   order to be created.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Nullable
    public VirtualDevice createVirtualDevice(int associationId) {
        // TODO(b/194949534): Unhide this API
        try {
            IVirtualDevice virtualDevice = mService.createVirtualDevice(
                    new Binder(), mContext.getPackageName(), associationId);
            return new VirtualDevice(mContext, virtualDevice);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A virtual device has its own virtual display, audio output, microphone, and camera etc. The
     * creator of a virtual device can take the output from the virtual display and stream it over
     * to another device, and inject input events that are received from the remote device.
     *
     * TODO(b/204081582): Consider using a builder pattern for the input APIs.
     */
    public static class VirtualDevice implements AutoCloseable {

        private final Context mContext;
        private final IVirtualDevice mVirtualDevice;

        private VirtualDevice(Context context, IVirtualDevice virtualDevice) {
            mContext = context.getApplicationContext();
            mVirtualDevice = virtualDevice;
        }

        /**
         * Closes the virtual device, stopping and tearing down any virtual displays,
         * audio policies, and event injection that's currently in progress.
         */
        public void close() {
            try {
                mVirtualDevice.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualKeyboard createVirtualKeyboard(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualKeyboard:" + inputDeviceName);
                mVirtualDevice.createVirtualKeyboard(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token);
                return new VirtualKeyboard(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual mouse.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualMouse createVirtualMouse(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualMouse:" + inputDeviceName);
                mVirtualDevice.createVirtualMouse(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token);
                return new VirtualMouse(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual touchscreen.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualTouchscreen createVirtualTouchscreen(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualTouchscreen:" + inputDeviceName);
                final Point size = new Point();
                display.getDisplay().getSize(size);
                mVirtualDevice.createVirtualTouchscreen(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token, size);
                return new VirtualTouchscreen(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
