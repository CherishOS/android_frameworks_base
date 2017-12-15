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

package android.telephony.ims.internal;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.internal.aidl.IImsConfig;
import android.telephony.ims.internal.aidl.IImsMmTelFeature;
import android.telephony.ims.internal.aidl.IImsRcsFeature;
import android.telephony.ims.internal.aidl.IImsRegistration;
import android.telephony.ims.internal.aidl.IImsServiceController;
import android.telephony.ims.internal.aidl.IImsServiceControllerListener;
import android.telephony.ims.internal.feature.ImsFeature;
import android.telephony.ims.internal.feature.MmTelFeature;
import android.telephony.ims.internal.feature.RcsFeature;
import android.telephony.ims.internal.stub.ImsConfigImplBase;
import android.telephony.ims.internal.stub.ImsFeatureConfiguration;
import android.telephony.ims.internal.stub.ImsRegistrationImplBase;
import android.util.Log;
import android.util.SparseArray;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Main ImsService implementation, which binds via the Telephony ImsResolver. Services that extend
 * ImsService must register the service in their AndroidManifest to be detected by the framework.
 * First, the application must declare that they use the "android.permission.BIND_IMS_SERVICE"
 * permission. Then, the ImsService definition in the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgImsService"
 *     android:permission="android.permission.BIND_IMS_SERVICE" >
 *     <!-- Apps must declare which features they support as metadata. The different categories are
 *     defined below. In this example, the RCS_FEATURE feature is supported. -->
 *     <meta-data android:name="android.telephony.ims.RCS_FEATURE" android:value="true" />
 *     <intent-filter>
 *         <action android:name="android.telephony.ims.ImsService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the ImsService you have defined in your manifest
 * if you are either:
 * 1) Defined as the default ImsService for the device in the device overlay using
 *    "config_ims_package".
 * 2) Defined as a Carrier Provided ImsService in the Carrier Configuration using
 *    {@link CarrierConfigManager#KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING}.
 *
 * The features that are currently supported in an ImsService are:
 * - RCS_FEATURE: This ImsService implements the RcsFeature class.
 * - MMTEL_FEATURE: This ImsService implements the MmTelFeature class.
 *   @hide
 */
public class ImsService extends Service {

    private static final String LOG_TAG = "ImsService";

    /**
     * The intent that must be defined as an intent-filter in the AndroidManifest of the ImsService.
     * @hide
     */
    public static final String SERVICE_INTERFACE = "android.telephony.ims.ImsService";

    // A map of slot Id -> map of features (indexed by ImsFeature feature id) corresponding to that
    // slot.
    // We keep track of this to facilitate cleanup of the IImsFeatureStatusCallback and
    // call ImsFeature#onFeatureRemoved.
    private final SparseArray<SparseArray<ImsFeature>> mFeaturesBySlot = new SparseArray<>();

    private IImsServiceControllerListener mListener;


    /**
     * Listener that notifies the framework of ImsService changes.
     */
    public static class Listener extends IImsServiceControllerListener.Stub {
        /**
         * The IMS features that this ImsService supports has changed.
         * @param c a new {@link ImsFeatureConfiguration} containing {@link ImsFeature.FeatureType}s
         *   that this ImsService supports. This may trigger the addition/removal of feature
         *   in this service.
         */
        public void onUpdateSupportedImsFeatures(ImsFeatureConfiguration c) {
        }
    }

    /**
     * @hide
     */
    protected final IBinder mImsServiceController = new IImsServiceController.Stub() {
        @Override
        public void setListener(IImsServiceControllerListener l) {
            mListener = l;
        }

        @Override
        public IImsMmTelFeature createMmTelFeature(int slotId, IImsFeatureStatusCallback c) {
            return createMmTelFeatureInternal(slotId, c);
        }

        @Override
        public IImsRcsFeature createRcsFeature(int slotId, IImsFeatureStatusCallback c) {
            return createRcsFeatureInternal(slotId, c);
        }

        @Override
        public void removeImsFeature(int slotId, int featureType, IImsFeatureStatusCallback c)
                throws RemoteException {
            ImsService.this.removeImsFeature(slotId, featureType, c);
        }

        @Override
        public ImsFeatureConfiguration querySupportedImsFeatures() {
            return ImsService.this.querySupportedImsFeatures();
        }

        @Override
        public void notifyImsServiceReadyForFeatureCreation() {
            ImsService.this.readyForFeatureCreation();
        }

        @Override
        public void notifyImsFeatureReady(int slotId, int featureType)
                throws RemoteException {
            ImsService.this.notifyImsFeatureReady(slotId, featureType);
        }

        @Override
        public IImsConfig getConfig(int slotId) throws RemoteException {
            ImsConfigImplBase c = ImsService.this.getConfig(slotId);
            return c != null ? c.getBinder() : null;
        }

        @Override
        public IImsRegistration getRegistration(int slotId) throws RemoteException {
            ImsRegistrationImplBase r = ImsService.this.getRegistration(slotId);
            return r != null ? r.getBinder() : null;
        }
    };

    /**
     * @hide
     */
    @Override
    public IBinder onBind(Intent intent) {
        if(SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.i(LOG_TAG, "ImsService Bound.");
            return mImsServiceController;
        }
        return null;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public SparseArray<ImsFeature> getFeatures(int slotId) {
        return mFeaturesBySlot.get(slotId);
    }

    private IImsMmTelFeature createMmTelFeatureInternal(int slotId,
            IImsFeatureStatusCallback c) {
        MmTelFeature f = createMmTelFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.FEATURE_MMTEL, c);
            return f.getBinder();
        } else {
            Log.e(LOG_TAG, "createMmTelFeatureInternal: null feature returned.");
            return null;
        }
    }

    private IImsRcsFeature createRcsFeatureInternal(int slotId,
            IImsFeatureStatusCallback c) {
        RcsFeature f = createRcsFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.FEATURE_RCS, c);
            return f.getBinder();
        } else {
            Log.e(LOG_TAG, "createRcsFeatureInternal: null feature returned.");
            return null;
        }
    }

    private void setupFeature(ImsFeature f, int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        f.addImsFeatureStatusCallback(c);
        f.initialize(this, slotId);
        addImsFeature(slotId, featureType, f);
    }

    private void addImsFeature(int slotId, int featureType, ImsFeature f) {
        synchronized (mFeaturesBySlot) {
            // Get SparseArray for Features, by querying slot Id
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                // Populate new SparseArray of features if it doesn't exist for this slot yet.
                features = new SparseArray<>();
                mFeaturesBySlot.put(slotId, features);
            }
            features.put(featureType, f);
        }
    }

    private void removeImsFeature(int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not remove ImsFeature. No ImsFeatures exist on slot "
                        + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f == null) {
                Log.w(LOG_TAG, "Can not remove ImsFeature. No feature with type "
                        + featureType + " exists on slot " + slotId);
                return;
            }
            f.removeImsFeatureStatusCallback(c);
            f.onFeatureRemoved();
            features.remove(featureType);
        }
    }

    private void notifyImsFeatureReady(int slotId, int featureType) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not notify ImsFeature ready. No ImsFeatures exist on " +
                        "slot " + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f == null) {
                Log.w(LOG_TAG, "Can not notify ImsFeature ready. No feature with type "
                        + featureType + " exists on slot " + slotId);
                return;
            }
            f.onFeatureReady();
        }
    }

    /**
     * When called, provide the {@link ImsFeatureConfiguration} that this ImsService currently
     * supports. This will trigger the framework to set up the {@link ImsFeature}s that correspond
     * to the {@link ImsFeature.FeatureType}s configured here.
     * @return an {@link ImsFeatureConfiguration} containing Features this ImsService supports,
     * defined in {@link ImsFeature.FeatureType}.
     */
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        // Return empty for base implementation
        return new ImsFeatureConfiguration();
    }

    /**
     * Updates the framework with a new {@link ImsFeatureConfiguration} containing the updated
     * features, defined in {@link ImsFeature.FeatureType} that this ImsService supports. This may
     * trigger the framework to add/remove new ImsFeatures, depending on the configuration.
     */
    public final void onUpdateSupportedImsFeatures(ImsFeatureConfiguration c)
            throws RemoteException {
        if (mListener == null) {
            throw new IllegalStateException("Framework is not ready");
        }
        mListener.onUpdateSupportedImsFeatures(c);
    }

    /**
     * The ImsService has been bound and is ready for ImsFeature creation based on the Features that
     * the ImsService has registered for with the framework, either in the manifest or via
     * The ImsService should use this signal instead of onCreate/onBind or similar to perform
     * feature initialization because the framework may bind to this service multiple times to
     * query the ImsService's {@link ImsFeatureConfiguration} via
     * {@link #querySupportedImsFeatures()}before creating features.
     */
    public void readyForFeatureCreation() {
    }

    /**
     * When called, the framework is requesting that a new MmTelFeature is created for the specified
     * slot.
     *
     * @param slotId The slot ID that the MMTel Feature is being created for.
     * @return The newly created MmTelFeature associated with the slot or null if the feature is not
     * supported.
     */
    public MmTelFeature createMmTelFeature(int slotId) {
        return null;
    }

    /**
     * When called, the framework is requesting that a new RcsFeature is created for the specified
     * slot
     *
     * @param slotId The slot ID that the RCS Feature is being created for.
     * @return The newly created RcsFeature associated with the slot or null if the feature is not
     * supported.
     */
    public RcsFeature createRcsFeature(int slotId) {
        return null;
    }

    /**
     * @param slotId The slot that the IMS configuration is associated with.
     * @return ImsConfig implementation that is associated with the specified slot.
     */
    public ImsConfigImplBase getConfig(int slotId) {
        return new ImsConfigImplBase();
    }

    /**
     * @param slotId The slot that is associated with the IMS Registration.
     * @return the ImsRegistration implementation associated with the slot.
     */
    public ImsRegistrationImplBase getRegistration(int slotId) {
        return new ImsRegistrationImplBase();
    }
}
