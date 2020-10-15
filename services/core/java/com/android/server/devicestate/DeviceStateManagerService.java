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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.devicestate.IDeviceStateManager;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;
import com.android.server.policy.DeviceStatePolicyImpl;

import java.util.Arrays;

/**
 * A system service that manages the state of a device with user-configurable hardware like a
 * foldable phone.
 * <p>
 * Device state is an abstract concept that allows mapping the current state of the device to the
 * state of the system. For example, system services (like
 * {@link com.android.server.display.DisplayManagerService display manager} and
 * {@link com.android.server.wm.WindowManagerService window manager}) and system UI may have
 * different behaviors depending on the physical state of the device. This is useful for
 * variable-state devices, like foldable or rollable devices, that can be configured by users into
 * differing hardware states, which each may have a different expected use case.
 * </p>
 * <p>
 * The {@link DeviceStateManagerService} is responsible for receiving state change requests from
 * the {@link DeviceStateProvider} to modify the current device state and communicating with the
 * {@link DeviceStatePolicy policy} to ensure the system is configured to match the requested state.
 * </p>
 *
 * @see DeviceStatePolicy
 */
public final class DeviceStateManagerService extends SystemService {
    private static final String TAG = "DeviceStateManagerService";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    @NonNull
    private final DeviceStatePolicy mDeviceStatePolicy;

    @GuardedBy("mLock")
    private IntArray mSupportedDeviceStates;

    // The current committed device state.
    @GuardedBy("mLock")
    private int mCommittedState = INVALID_DEVICE_STATE;
    // The device state that is currently pending callback from the policy to be committed.
    @GuardedBy("mLock")
    private int mPendingState = INVALID_DEVICE_STATE;
    // Whether or not the policy is currently waiting to be notified of the current pending state.
    @GuardedBy("mLock")
    private boolean mIsPolicyWaitingForState = false;
    // The device state that is currently requested and is next to be configured and committed.
    @GuardedBy("mLock")
    private int mRequestedState = INVALID_DEVICE_STATE;

    public DeviceStateManagerService(@NonNull Context context) {
        this(context, new DeviceStatePolicyImpl());
    }

    @VisibleForTesting
    DeviceStateManagerService(@NonNull Context context, @NonNull DeviceStatePolicy policy) {
        super(context);
        mDeviceStatePolicy = policy;
    }

    @Override
    public void onStart() {
        mDeviceStatePolicy.getDeviceStateProvider().setListener(new DeviceStateProviderListener());
        publishBinderService(Context.DEVICE_STATE_SERVICE, new BinderService());
    }

    /**
     * Returns the current state the system is in. Note that the system may be in the process of
     * configuring a different state.
     *
     * @see #getPendingState()
     */
    @VisibleForTesting
    int getCommittedState() {
        synchronized (mLock) {
            return mCommittedState;
        }
    }

    /**
     * Returns the state the system is currently configuring, or {@link #INVALID_DEVICE_STATE} if
     * the system is not in the process of configuring a state.
     */
    @VisibleForTesting
    int getPendingState() {
        synchronized (mLock) {
            return mPendingState;
        }
    }

    /**
     * Returns the requested state. The service will configure the device to match the requested
     * state when possible.
     */
    @VisibleForTesting
    int getRequestedState() {
        synchronized (mLock) {
            return mRequestedState;
        }
    }

    private void updateSupportedStates(int[] supportedDeviceStates) {
        // Must ensure sorted as isSupportedStateLocked() impl uses binary search.
        Arrays.sort(supportedDeviceStates, 0, supportedDeviceStates.length);
        synchronized (mLock) {
            mSupportedDeviceStates = IntArray.wrap(supportedDeviceStates);

            if (mRequestedState != INVALID_DEVICE_STATE
                    && !isSupportedStateLocked(mRequestedState)) {
                // The current requested state is no longer valid. We'll clear it here, though
                // we won't actually update the current state with a call to
                // updatePendingStateLocked() as doing so will not have any effect.
                mRequestedState = INVALID_DEVICE_STATE;
            }
        }
    }

    /**
     * Returns {@code true} if the provided state is supported. Requires that
     * {@link #mSupportedDeviceStates} is sorted prior to calling.
     */
    private boolean isSupportedStateLocked(int state) {
        return mSupportedDeviceStates.binarySearch(state) >= 0;
    }

    /**
     * Requests that the system enter the provided {@code state}. The request may not be honored
     * under certain conditions, for example if the provided state is not supported.
     *
     * @see #isSupportedStateLocked(int)
     */
    private void requestState(int state) {
        synchronized (mLock) {
            if (isSupportedStateLocked(state)) {
                mRequestedState = state;
            }
            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
    }

    /**
     * Tries to update the current configuring state with the current requested state. Must call
     * {@link #notifyPolicyIfNeeded()} to actually notify the policy that the state is being
     * changed.
     */
    private void updatePendingStateLocked() {
        if (mRequestedState == INVALID_DEVICE_STATE) {
            // No currently requested state.
            return;
        }

        if (mPendingState != INVALID_DEVICE_STATE) {
            // Have pending state, can not configure a new state until the state is committed.
            return;
        }

        if (mRequestedState == mCommittedState) {
            // No need to notify the policy as the committed state matches the requested state.
            return;
        }

        mPendingState = mRequestedState;
        mIsPolicyWaitingForState = true;
    }

    /**
     * Notifies the policy to configure the supplied state. Should not be called with {@link #mLock}
     * held.
     */
    private void notifyPolicyIfNeeded() {
        if (Thread.holdsLock(mLock)) {
            Throwable error = new Throwable("Attempting to notify DeviceStatePolicy with service"
                    + " lock held");
            error.fillInStackTrace();
            Slog.w(TAG, error);
        }
        int state;
        synchronized (mLock) {
            if (!mIsPolicyWaitingForState) {
                return;
            }
            mIsPolicyWaitingForState = false;
            state = mPendingState;
        }

        if (DEBUG) {
            Slog.d(TAG, "Notifying policy to configure state: " + state);
        }
        mDeviceStatePolicy.configureDeviceForState(state, this::commitPendingState);
    }

    /**
     * Commits the current pending state after a callback from the {@link DeviceStatePolicy}.
     *
     * <pre>
     *              -------------    -----------              -------------
     * Provider ->  | Requested | -> | Pending | -> Policy -> | Committed |
     *              -------------    -----------              -------------
     * </pre>
     * <p>
     * When a new state is requested it immediately enters the requested state. Once the policy is
     * available to accept a new state, which could also be immediately if there is no current
     * pending state at the point of request, the policy is notified and a callback is provided to
     * trigger the state to be committed.
     * </p>
     */
    private void commitPendingState() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Committing state: " + mPendingState);
            }
            mCommittedState = mPendingState;
            mPendingState = INVALID_DEVICE_STATE;
            updatePendingStateLocked();
        }

        notifyPolicyIfNeeded();
    }

    private final class DeviceStateProviderListener implements DeviceStateProvider.Listener {
        @Override
        public void onSupportedDeviceStatesChanged(int[] newDeviceStates) {
            for (int i = 0; i < newDeviceStates.length; i++) {
                if (newDeviceStates[i] < 0) {
                    throw new IllegalArgumentException("Supported device states includes invalid"
                            + " value: " + newDeviceStates[i]);
                }
            }

            updateSupportedStates(newDeviceStates);
        }

        @Override
        public void onStateChanged(int state) {
            if (state < 0) {
                throw new IllegalArgumentException("Invalid state: " + state);
            }

            requestState(state);
        }
    }

    /** Implementation of {@link IDeviceStateManager} published as a binder service. */
    private final class BinderService extends IDeviceStateManager.Stub {

    }
}
