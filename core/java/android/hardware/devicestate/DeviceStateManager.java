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

package android.hardware.devicestate;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;

import java.util.concurrent.Executor;

/**
 * Manages the state of the system for devices with user-configurable hardware like a foldable
 * phone.
 *
 * @hide
 */
@TestApi
@SystemService(Context.DEVICE_STATE_SERVICE)
public final class DeviceStateManager {
    /**
     * Invalid device state.
     *
     * @hide
     */
    public static final int INVALID_DEVICE_STATE = -1;

    /** The minimum allowed device state identifier. */
    public static final int MINIMUM_DEVICE_STATE = 0;

    /** The maximum allowed device state identifier. */
    public static final int MAXIMUM_DEVICE_STATE = 255;

    private final DeviceStateManagerGlobal mGlobal;

    /** @hide */
    public DeviceStateManager() {
        DeviceStateManagerGlobal global = DeviceStateManagerGlobal.getInstance();
        if (global == null) {
            throw new IllegalStateException(
                    "Failed to get instance of global device state manager.");
        }
        mGlobal = global;
    }

    /**
     * Returns the list of device states that are supported and can be requested with
     * {@link #requestState(DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     */
    @NonNull
    public int[] getSupportedStates() {
        return mGlobal.getSupportedStates();
    }

    /**
     * Submits a {@link DeviceStateRequest request} to modify the device state.
     * <p>
     * By default, the request is kept active until a call to
     * {@link #cancelRequest(DeviceStateRequest)} or until one of the following occurs:
     * <ul>
     *     <li>Another processes submits a request succeeding this request in which case the request
     *     will be suspended until the interrupting request is canceled.
     *     <li>The requested state has become unsupported.
     *     <li>The process submitting the request dies.
     * </ul>
     * However, this behavior can be changed by setting flags on the {@link DeviceStateRequest}.
     *
     * @throws IllegalArgumentException if the requested state is unsupported.
     * @throws SecurityException if the {@link android.Manifest.permission#CONTROL_DEVICE_STATE}
     * permission is not held.
     *
     * @see DeviceStateRequest
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    public void requestState(@NonNull DeviceStateRequest request,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable DeviceStateRequest.Callback callback) {
        mGlobal.requestState(request, callback, executor);
    }

    /**
     * Cancels a {@link DeviceStateRequest request} previously submitted with a call to
     * {@link #requestState(DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     * <p>
     * This method is noop if the {@code request} has not been submitted with a call to
     * {@link #requestState(DeviceStateRequest, Executor, DeviceStateRequest.Callback)}.
     *
     * @throws SecurityException if the {@link android.Manifest.permission#CONTROL_DEVICE_STATE}
     * permission is not held.
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)
    public void cancelRequest(@NonNull DeviceStateRequest request) {
        mGlobal.cancelRequest(request);
    }

    /**
     * Registers a listener to receive notifications about changes in device state.
     *
     * @param executor the executor to process notifications.
     * @param listener the listener to register.
     *
     * @see DeviceStateListener
     */
    public void addDeviceStateListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull DeviceStateListener listener) {
        mGlobal.registerDeviceStateListener(listener, executor);
    }

    /**
     * Unregisters a listener previously registered with
     * {@link #addDeviceStateListener(Executor, DeviceStateListener)}.
     */
    public void removeDeviceStateListener(@NonNull DeviceStateListener listener) {
        mGlobal.unregisterDeviceStateListener(listener);
    }

    /**
     * Listens for changes in device states.
     */
    public interface DeviceStateListener {
        /**
         * Called in response to device state changes.
         * <p>
         * Guaranteed to be called once on registration of the listener with the
         * initial value and then on every subsequent change in device state.
         *
         * @param deviceState the new device state.
         */
        void onDeviceStateChanged(int deviceState);
    }
}
