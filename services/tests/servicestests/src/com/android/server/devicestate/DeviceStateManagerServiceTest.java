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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;

import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.devicestate.IDeviceStateManagerCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Unit tests for {@link DeviceStateManagerService}.
 * <p/>
 * Run with <code>atest DeviceStateManagerServiceTest</code>.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DeviceStateManagerServiceTest {
    private static final DeviceState DEFAULT_DEVICE_STATE = new DeviceState(0, "DEFAULT");
    private static final DeviceState OTHER_DEVICE_STATE = new DeviceState(1, "OTHER");
    // A device state that is not reported as being supported for the default test provider.
    private static final DeviceState UNSUPPORTED_DEVICE_STATE = new DeviceState(255, "UNSUPPORTED");

    private TestDeviceStatePolicy mPolicy;
    private TestDeviceStateProvider mProvider;
    private DeviceStateManagerService mService;

    @Before
    public void setup() {
        mProvider = new TestDeviceStateProvider();
        mPolicy = new TestDeviceStatePolicy(mProvider);
        mService = new DeviceStateManagerService(InstrumentationRegistry.getContext(), mPolicy);
    }

    @Test
    public void baseStateChanged() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void baseStateChanged_withStatePendingPolicyCallback() {
        mPolicy.blockConfigure();

        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState().get(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.setState(DEFAULT_DEVICE_STATE.getIdentifier());
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState().get(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mPolicy.resumeConfigure();
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void baseStateChanged_unsupportedState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.setState(UNSUPPORTED_DEVICE_STATE.getIdentifier());
        });

        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void baseStateChanged_invalidState() {
        assertThrows(IllegalArgumentException.class, () -> {
            mProvider.setState(INVALID_DEVICE_STATE);
        });

        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void supportedStatesChanged() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ DEFAULT_DEVICE_STATE });

        // The current committed and requests states do not change because the current state remains
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
    }

    @Test
    public void supportedStatesChanged_unsupportedBaseState() {
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ OTHER_DEVICE_STATE });

        // The current requested state is cleared because it is no longer supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState(), Optional.empty());

        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());

        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getPendingState(), Optional.empty());
        assertEquals(mService.getBaseState().get(), OTHER_DEVICE_STATE);
    }

    @Test
    public void registerCallback() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());
        assertNotNull(callback.getLastNotifiedValue());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.setState(DEFAULT_DEVICE_STATE.getIdentifier());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mPolicy.blockConfigure();
        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());
        // The callback should not have been notified of the state change as the policy is still
        // pending callback.
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());

        mPolicy.resumeConfigure();
        // Now that the policy is finished processing the callback should be notified of the state
        // change.
        assertEquals(callback.getLastNotifiedValue().intValue(),
                OTHER_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void registerCallback_emitsInitialValue() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);
        assertNotNull(callback.getLastNotifiedValue());
        assertEquals(callback.getLastNotifiedValue().intValue(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void getSupportedDeviceStates() throws RemoteException {
        final int[] expectedStates = new int[] { 0, 1 };
        assertEquals(mService.getBinderService().getSupportedDeviceStates(), expectedStates);
    }

    @Test
    public void requestState() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        final IBinder token = new Binder();
        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestState(token, OTHER_DEVICE_STATE.getIdentifier(),
                0 /* flags */);

        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getOverrideState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());


        mService.getBinderService().cancelRequest(token);

        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state once the override is cleared.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertFalse(mService.getOverrideState().isPresent());
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestState_flagCancelWhenBaseChanges() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        final IBinder token = new Binder();
        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestState(token, OTHER_DEVICE_STATE.getIdentifier(),
                DeviceStateRequest.FLAG_CANCEL_WHEN_BASE_CHANGES);

        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_ACTIVE);

        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getOverrideState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.setState(OTHER_DEVICE_STATE.getIdentifier());

        // Request is canceled because the base state changed.
        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state once the override is cleared.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), OTHER_DEVICE_STATE);
        assertFalse(mService.getOverrideState().isPresent());
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestState_becomesUnsupported() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        final IBinder token = new Binder();
        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_UNKNOWN);

        mService.getBinderService().requestState(token, OTHER_DEVICE_STATE.getIdentifier(),
                0 /* flags */);

        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_ACTIVE);
        // Committed state changes as there is a requested override.
        assertEquals(mService.getCommittedState(), OTHER_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getOverrideState().get(), OTHER_DEVICE_STATE);
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                OTHER_DEVICE_STATE.getIdentifier());

        mProvider.notifySupportedDeviceStates(new DeviceState[]{ DEFAULT_DEVICE_STATE });

        // Request is canceled because the state is no longer supported.
        assertEquals(callback.getLastNotifiedStatus(token),
                TestDeviceStateManagerCallback.STATUS_CANCELED);
        // Committed state is set back to the requested state as the override state is no longer
        // supported.
        assertEquals(mService.getCommittedState(), DEFAULT_DEVICE_STATE);
        assertEquals(mService.getBaseState().get(), DEFAULT_DEVICE_STATE);
        assertFalse(mService.getOverrideState().isPresent());
        assertEquals(mPolicy.getMostRecentRequestedStateToConfigure(),
                DEFAULT_DEVICE_STATE.getIdentifier());
    }

    @Test
    public void requestState_unsupportedState() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestState(token,
                    UNSUPPORTED_DEVICE_STATE.getIdentifier(), 0 /* flags */);
        });
    }

    @Test
    public void requestState_invalidState() throws RemoteException {
        TestDeviceStateManagerCallback callback = new TestDeviceStateManagerCallback();
        mService.getBinderService().registerCallback(callback);

        assertThrows(IllegalArgumentException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestState(token, INVALID_DEVICE_STATE, 0 /* flags */);
        });
    }

    @Test
    public void requestState_beforeRegisteringCallback() {
        assertThrows(IllegalStateException.class, () -> {
            final IBinder token = new Binder();
            mService.getBinderService().requestState(token, DEFAULT_DEVICE_STATE.getIdentifier(),
                    0 /* flags */);
        });
    }

    private static final class TestDeviceStatePolicy implements DeviceStatePolicy {
        private final DeviceStateProvider mProvider;
        private int mLastDeviceStateRequestedToConfigure = INVALID_DEVICE_STATE;
        private boolean mConfigureBlocked = false;
        private Runnable mPendingConfigureCompleteRunnable;

        TestDeviceStatePolicy(DeviceStateProvider provider) {
            mProvider = provider;
        }

        @Override
        public DeviceStateProvider getDeviceStateProvider() {
            return mProvider;
        }

        public void blockConfigure() {
            mConfigureBlocked = true;
        }

        public void resumeConfigure() {
            mConfigureBlocked = false;
            if (mPendingConfigureCompleteRunnable != null) {
                Runnable onComplete = mPendingConfigureCompleteRunnable;
                mPendingConfigureCompleteRunnable = null;
                onComplete.run();
            }
        }

        public int getMostRecentRequestedStateToConfigure() {
            return mLastDeviceStateRequestedToConfigure;
        }

        @Override
        public void configureDeviceForState(int state, Runnable onComplete) {
            if (mPendingConfigureCompleteRunnable != null) {
                throw new IllegalStateException("configureDeviceForState() called while configure"
                        + " is pending");
            }

            mLastDeviceStateRequestedToConfigure = state;
            if (mConfigureBlocked) {
                mPendingConfigureCompleteRunnable = onComplete;
                return;
            }
            onComplete.run();
        }
    }

    private static final class TestDeviceStateProvider implements DeviceStateProvider {
        private DeviceState[] mSupportedDeviceStates = new DeviceState[]{ DEFAULT_DEVICE_STATE,
                OTHER_DEVICE_STATE };
        private Listener mListener;

        @Override
        public void setListener(Listener listener) {
            if (mListener != null) {
                throw new IllegalArgumentException("Provider already has listener set.");
            }

            mListener = listener;
            mListener.onSupportedDeviceStatesChanged(mSupportedDeviceStates);
            mListener.onStateChanged(mSupportedDeviceStates[0].getIdentifier());
        }

        public void notifySupportedDeviceStates(DeviceState[] supportedDeviceStates) {
            mSupportedDeviceStates = supportedDeviceStates;
            mListener.onSupportedDeviceStatesChanged(supportedDeviceStates);
        }

        public void setState(int identifier) {
            mListener.onStateChanged(identifier);
        }
    }

    private static final class TestDeviceStateManagerCallback extends
            IDeviceStateManagerCallback.Stub {
        public static final int STATUS_UNKNOWN = 0;
        public static final int STATUS_ACTIVE = 1;
        public static final int STATUS_SUSPENDED = 2;
        public static final int STATUS_CANCELED = 3;

        private Integer mLastNotifiedValue;
        private final HashMap<IBinder, Integer> mLastNotifiedStatus = new HashMap<>();

        @Override
        public void onDeviceStateChanged(int deviceState) {
            mLastNotifiedValue = deviceState;
        }

        @Override
        public void onRequestActive(IBinder token) {
            mLastNotifiedStatus.put(token, STATUS_ACTIVE);
        }

        @Override
        public void onRequestSuspended(IBinder token) {
            mLastNotifiedStatus.put(token, STATUS_SUSPENDED);
        }

        @Override
        public void onRequestCanceled(IBinder token) {
            mLastNotifiedStatus.put(token, STATUS_CANCELED);
        }

        @Nullable
        Integer getLastNotifiedValue() {
            return mLastNotifiedValue;
        }

        int getLastNotifiedStatus(IBinder requestToken) {
            return mLastNotifiedStatus.getOrDefault(requestToken, STATUS_UNKNOWN);
        }
    }
}
