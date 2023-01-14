/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.hdmi;

import android.hardware.tv.hdmi.earc.IEArc;
import android.hardware.tv.hdmi.earc.IEArcCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;

import com.android.internal.annotations.VisibleForTesting;

final class HdmiEarcController {
    private static final String TAG = "HdmiEarcController";

    // Handler instance to process HAL calls.
    private Handler mControlHandler;

    private final HdmiControlService mService;

    private EArcNativeWrapper mEArcNativeWrapperImpl;

    protected interface EArcNativeWrapper {
        boolean nativeInit();
        void nativeSetEArcEnabled(boolean enabled);
        boolean nativeIsEArcEnabled();
        void nativeSetCallback(EarcAidlCallback callback);
        byte nativeGetState(int portId);
        byte[] nativeGetLastReportedAudioCapabilities(int portId);
    }

    private static final class EArcNativeWrapperImpl implements EArcNativeWrapper,
            IBinder.DeathRecipient {
        private IEArc mEArc;
        private EarcAidlCallback mEArcCallback;

        @Override
        public void binderDied() {
            mEArc.asBinder().unlinkToDeath(this, 0);
            connectToHal();
            if (mEArcCallback != null) {
                nativeSetCallback(mEArcCallback);
            }
        }

        boolean connectToHal() {
            mEArc =
                    IEArc.Stub.asInterface(
                            ServiceManager.getService(IEArc.DESCRIPTOR + "/default"));
            if (mEArc == null) {
                return false;
            }
            try {
                mEArc.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't link callback object: ", e);
            }
            return true;
        }

        @Override
        public boolean nativeInit() {
            return connectToHal();
        }

        @Override
        public void nativeSetEArcEnabled(boolean enabled) {
            try {
                mEArc.setEArcEnabled(enabled);
            } catch (ServiceSpecificException sse) {
                HdmiLogger.error(
                        "Could not set eARC enabled to " + enabled + ". Error: ", sse.errorCode);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not set eARC enabled to " + enabled + ":. Exception: ", re);
            }
        }

        @Override
        public boolean nativeIsEArcEnabled() {
            try {
                return mEArc.isEArcEnabled();
            } catch (RemoteException re) {
                HdmiLogger.error("Could not read if eARC is enabled. Exception: ", re);
                return false;
            }
        }

        @Override
        public void nativeSetCallback(EarcAidlCallback callback) {
            mEArcCallback = callback;
            try {
                mEArc.setCallback(callback);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not set callback. Exception: ", re);
            }
        }

        @Override
        public byte nativeGetState(int portId) {
            try {
                return mEArc.getState(portId);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not get eARC state. Exception: ", re);
                return -1;
            }
        }

        @Override
        public byte[] nativeGetLastReportedAudioCapabilities(int portId) {
            try {
                return mEArc.getLastReportedAudioCapabilities(portId);
            } catch (RemoteException re) {
                HdmiLogger.error(
                        "Could not read last reported audio capabilities. Exception: ", re);
                return null;
            }
        }
    }

    // Private constructor. Use HdmiEarcController.create().
    private HdmiEarcController(HdmiControlService service, EArcNativeWrapper nativeWrapper) {
        mService = service;
        mEArcNativeWrapperImpl = nativeWrapper;
    }

    /**
     * A factory method to get {@link HdmiEarcController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service    {@link HdmiControlService} instance used to create internal handler
     *                   and to pass callback for incoming message or event.
     * @return {@link HdmiEarcController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiEarcController create(HdmiControlService service) {
        return createWithNativeWrapper(service, new EArcNativeWrapperImpl());
    }

    /**
     * A factory method with injection of native methods for testing.
     */
    static HdmiEarcController createWithNativeWrapper(HdmiControlService service,
            EArcNativeWrapper nativeWrapper) {
        HdmiEarcController controller = new HdmiEarcController(service, nativeWrapper);
        if (!controller.init(nativeWrapper)) {
            HdmiLogger.warning("Could not connect to eARC AIDL HAL.");
            return null;
        }
        return controller;
    }

    private boolean init(EArcNativeWrapper nativeWrapper) {
        if (nativeWrapper.nativeInit()) {
            mControlHandler = new Handler(mService.getServiceLooper());
            mEArcNativeWrapperImpl.nativeSetCallback(new EarcAidlCallback());
            return true;
        }
        return false;
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mControlHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    @VisibleForTesting
    void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(new WorkSourceUidPreservingRunnable(runnable));
    }

    /**
     * Enable eARC in the HAL
     * @param enabled
     */
    @HdmiAnnotations.ServiceThreadOnly
    void setEarcEnabled(boolean enabled) {
        assertRunOnServiceThread();
        mEArcNativeWrapperImpl.nativeSetEArcEnabled(enabled);
    }

    /**
     * Getter for the current eARC state.
     * @param portId the ID of the port on which to get the connection state
     * @return the current eARC state
     */
    @HdmiAnnotations.ServiceThreadOnly
    @Constants.EarcStatus
    int getState(int portId) {
        return mEArcNativeWrapperImpl.nativeGetState(portId);
    }

    /**
     * Ask the HAL to report the last eARC capabilities that the connected audio system reported.
     *
     * @return the raw eARC capabilities
     */
    @HdmiAnnotations.ServiceThreadOnly
    byte[] getLastReportedCaps(int portId) {
        return mEArcNativeWrapperImpl.nativeGetLastReportedAudioCapabilities(portId);
    }

    final class EarcAidlCallback extends IEArcCallback.Stub {
        public void onStateChange(@Constants.EarcStatus byte status, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcStateChange(status, portId));
        }

        public void onCapabilitiesReported(byte[] rawCapabilities, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcCapabilitiesReported(rawCapabilities, portId));
        }

        @Override
        public synchronized String getInterfaceHash() throws RemoteException {
            return IEArcCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return IEArcCallback.Stub.VERSION;
        }
    }
}
