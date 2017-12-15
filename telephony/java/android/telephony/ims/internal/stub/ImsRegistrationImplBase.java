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
 * limitations under the License
 */

package android.telephony.ims.internal.stub;

import android.annotation.IntDef;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.ims.internal.aidl.IImsRegistration;
import android.telephony.ims.internal.aidl.IImsRegistrationCallback;
import android.util.Log;

import com.android.ims.ImsReasonInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controls IMS registration for this ImsService and notifies the framework when the IMS
 * registration for this ImsService has changed status.
 * @hide
 */

public class ImsRegistrationImplBase {

    private static final String LOG_TAG = "ImsRegistrationImplBase";

    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(flag = true,
            value = {
                    REGISTRATION_TECH_NONE,
                    REGISTRATION_TECH_LTE,
                    REGISTRATION_TECH_IWLAN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsRegistrationTech {}
    /**
     * No registration technology specified, used when we are not registered.
     */
    public static final int REGISTRATION_TECH_NONE = -1;
    /**
     * IMS is registered to IMS via LTE.
     */
    public static final int REGISTRATION_TECH_LTE = 0;
    /**
     * IMS is registered to IMS via IWLAN.
     */
    public static final int REGISTRATION_TECH_IWLAN = 1;

    // Registration states, used to notify new ImsRegistrationImplBase#Callbacks of the current
    // state.
    private static final int REGISTRATION_STATE_NOT_REGISTERED = 0;
    private static final int REGISTRATION_STATE_REGISTERING = 1;
    private static final int REGISTRATION_STATE_REGISTERED = 2;


    /**
     * Callback class for receiving Registration callback events.
     */
    public static class Callback extends IImsRegistrationCallback.Stub {

        /**
         * Notifies the framework when the IMS Provider is connected to the IMS network.
         *
         * @param imsRadioTech the radio access technology. Valid values are defined in
         * {@link ImsRegistrationTech}.
         */
        @Override
        public void onRegistered(@ImsRegistrationTech int imsRadioTech) {
        }

        /**
         * Notifies the framework when the IMS Provider is trying to connect the IMS network.
         *
         * @param imsRadioTech the radio access technology. Valid values are defined in
         * {@link ImsRegistrationTech}.
         */
        @Override
        public void onRegistering(@ImsRegistrationTech int imsRadioTech) {
        }

        /**
         * Notifies the framework when the IMS Provider is disconnected from the IMS network.
         *
         * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
         */
        @Override
        public void onDeregistered(ImsReasonInfo info) {
        }

        /**
         * A failure has occurred when trying to handover registration to another technology type,
         * defined in {@link ImsRegistrationTech}
         *
         * @param imsRadioTech The {@link ImsRegistrationTech} type that has failed
         * @param info A {@link ImsReasonInfo} that identifies the reason for failure.
         */
        @Override
        public void onTechnologyChangeFailed(@ImsRegistrationTech int imsRadioTech,
                ImsReasonInfo info) {
        }
    }

    private final IImsRegistration mBinder = new IImsRegistration.Stub() {

        @Override
        public @ImsRegistrationTech int getRegistrationTechnology() throws RemoteException {
            return getConnectionType();
        }

        @Override
        public void addRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.addRegistrationCallback(c);
        }

        @Override
        public void removeRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.removeRegistrationCallback(c);
        }
    };

    private final RemoteCallbackList<IImsRegistrationCallback> mCallbacks
            = new RemoteCallbackList<>();
    private final Object mLock = new Object();
    // Locked on mLock
    private @ImsRegistrationTech
    int mConnectionType = REGISTRATION_TECH_NONE;
    // Locked on mLock
    private int mRegistrationState = REGISTRATION_STATE_NOT_REGISTERED;
    // Locked on mLock
    private ImsReasonInfo mLastDisconnectCause;

    public final IImsRegistration getBinder() {
        return mBinder;
    }

    private void addRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
        mCallbacks.register(c);
        updateNewCallbackWithState(c);
    }

    private void removeRegistrationCallback(IImsRegistrationCallback c) {
        mCallbacks.unregister(c);
    }

    /**
     * Notify the framework that the device is connected to the IMS network.
     *
     * @param imsRadioTech the radio access technology. Valid values are defined in
     * {@link ImsRegistrationTech}.
     */
    public final void onRegistered(@ImsRegistrationTech int imsRadioTech) {
        updateToState(imsRadioTech, REGISTRATION_STATE_REGISTERED);
        mCallbacks.broadcast((c) -> {
            try {
                c.onRegistered(imsRadioTech);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationConnected() - Skipping " +
                        "callback.");
            }
        });
    }

    /**
     * Notify the framework that the device is trying to connect the IMS network.
     *
     * @param imsRadioTech the radio access technology. Valid values are defined in
     * {@link ImsRegistrationTech}.
     */
    public final void onRegistering(@ImsRegistrationTech int imsRadioTech) {
        updateToState(imsRadioTech, REGISTRATION_STATE_REGISTERING);
        mCallbacks.broadcast((c) -> {
            try {
                c.onRegistering(imsRadioTech);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationProcessing() - Skipping " +
                        "callback.");
            }
        });
    }

    /**
     * Notify the framework that the device is disconnected from the IMS network.
     *
     * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
     */
    public final void onDeregistered(ImsReasonInfo info) {
        updateToDisconnectedState(info);
        mCallbacks.broadcast((c) -> {
            try {
                c.onDeregistered(info);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationDisconnected() - Skipping " +
                        "callback.");
            }
        });
    }

    public final void onTechnologyChangeFailed(@ImsRegistrationTech int imsRadioTech,
            ImsReasonInfo info) {
        mCallbacks.broadcast((c) -> {
            try {
                c.onTechnologyChangeFailed(imsRadioTech, info);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationChangeFailed() - Skipping " +
                        "callback.");
            }
        });
    }

    private void updateToState(@ImsRegistrationTech int connType, int newState) {
        synchronized (mLock) {
            mConnectionType = connType;
            mRegistrationState = newState;
            mLastDisconnectCause = null;
        }
    }

    private void updateToDisconnectedState(ImsReasonInfo info) {
        synchronized (mLock) {
            updateToState(REGISTRATION_TECH_NONE, REGISTRATION_STATE_NOT_REGISTERED);
            if (info != null) {
                mLastDisconnectCause = info;
            } else {
                Log.w(LOG_TAG, "updateToDisconnectedState: no ImsReasonInfo provided.");
                mLastDisconnectCause = new ImsReasonInfo();
            }
        }
    }

    private @ImsRegistrationTech int getConnectionType() {
        synchronized (mLock) {
            return mConnectionType;
        }
    }

    /**
     * @param c the newly registered callback that will be updated with the current registration
     *         state.
     */
    private void updateNewCallbackWithState(IImsRegistrationCallback c) throws RemoteException {
        int state;
        ImsReasonInfo disconnectInfo;
        synchronized (mLock) {
            state = mRegistrationState;
            disconnectInfo = mLastDisconnectCause;
        }
        switch (state) {
            case REGISTRATION_STATE_NOT_REGISTERED: {
                c.onDeregistered(disconnectInfo);
                break;
            }
            case REGISTRATION_STATE_REGISTERING: {
                c.onRegistering(getConnectionType());
                break;
            }
            case REGISTRATION_STATE_REGISTERED: {
                c.onRegistered(getConnectionType());
                break;
            }
        }
    }
}
